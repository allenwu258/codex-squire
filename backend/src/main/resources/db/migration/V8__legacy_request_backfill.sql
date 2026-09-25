INSERT INTO gateway_requests
    (id, received_at, completed_at, model, stream, scheme_id, final_provider_id,
     final_outcome, attempt_count, total_latency_ms)
SELECT a.request_id,
       MIN(a.started_at),
       CASE WHEN SUM(a.status = 'IN_FLIGHT') > 0 THEN NULL ELSE MAX(a.ended_at) END,
       (SELECT first.model FROM attempts first WHERE first.request_id = a.request_id
        ORDER BY first.started_at, first.id LIMIT 1),
       0,
       (SELECT first.scheme_id FROM attempts first WHERE first.request_id = a.request_id
        ORDER BY first.started_at, first.id LIMIT 1),
       (SELECT last.provider_id FROM attempts last WHERE last.request_id = a.request_id
        ORDER BY last.attempt_index DESC, last.started_at DESC, last.id DESC LIMIT 1),
       CASE WHEN SUM(a.status = 'IN_FLIGHT') > 0 THEN NULL ELSE
           (SELECT last.status FROM attempts last WHERE last.request_id = a.request_id
            ORDER BY last.attempt_index DESC, last.started_at DESC, last.id DESC LIMIT 1) END,
       COUNT(*),
       CASE WHEN SUM(a.status = 'IN_FLIGHT') > 0 THEN 0 ELSE
           CAST(MAX(0, (julianday(MAX(a.ended_at)) - julianday(MIN(a.started_at))) * 86400000) AS INTEGER) END
FROM attempts a
WHERE NOT EXISTS (SELECT 1 FROM gateway_requests g WHERE g.id = a.request_id)
GROUP BY a.request_id;
