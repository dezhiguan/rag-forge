-- V6 Judge: 一次性修复卡在 RUNNING 的历史行
--
-- 何时执行：
--   1. 部署 fix(judge) truncate failure_reason 补丁后
--   2. 发现 judge_results 存在 status=RUNNING 且 created_at 超过 10 分钟未更新
--   3. 日志出现 "value too long for type character varying(256)" 且 updateById 失败
--
-- 执行前建议：
--   SELECT id, answer_log_id, status, created_at, failure_reason
--   FROM judge_results
--   WHERE status = 'RUNNING'
--     AND created_at < NOW() - INTERVAL '10 minutes';

UPDATE judge_results
SET status = 'FAILED',
    failure_reason = COALESCE(failure_reason, 'STUCK_RUNNING_AUTORECOVERY')
WHERE status = 'RUNNING'
  AND created_at < NOW() - INTERVAL '10 minutes';
