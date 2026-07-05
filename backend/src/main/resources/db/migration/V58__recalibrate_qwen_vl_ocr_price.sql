-- V58__recalibrate_qwen_vl_ocr_price.sql
-- 拟制日期：2026-07-06  版本：V58
-- 目的：校准 qwen-vl-ocr 计价（元/百万 Token），区分输入 / 输出单价。
-- 背景：V37 曾按“0.006 元/千Token 输入输出同价”统一设为 6.00 / 6.00，
--       与实际计费口径不符，导致成本中心 OCR 计价虚高。
-- 变更：输入 6.00 → 0.30，输出 6.00 → 0.50（元/百万 Token）。
-- 影响：① 成本中心「输入单价 / 输出价」两列展示；
--       ② 之后每次 OCR 调用的成本计算（CostCalculator 读此价）。
--       历史已落库的 model_usage_daily.cost 不重算，仅影响新增计量。
UPDATE model_config
   SET input_price  = 0.30,
       output_price = 0.50,
       updated_at   = NOW()
 WHERE code = 'qwen-vl-ocr';
