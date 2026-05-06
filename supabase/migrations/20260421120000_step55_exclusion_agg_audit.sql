-- 步骤 5.5：无效数据标记（不参与聚合）+ 聚合过程审计字段
-- 在已有 calibration_records / device_profiles 及 update_device_profile 的基础上执行。

ALTER TABLE public.calibration_records
    ADD COLUMN IF NOT EXISTS excluded_from_aggregation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS exclusion_note TEXT;

COMMENT ON COLUMN public.calibration_records.excluded_from_aggregation IS
'后台在 Supabase（SQL Editor / service_role）将无效标定标为 true 后，该行不参与标准曲线聚合';
COMMENT ON COLUMN public.calibration_records.exclusion_note IS '无效原因说明（运维选填）';

ALTER TABLE public.device_profiles
    ADD COLUMN IF NOT EXISTS last_agg_eligible_count INTEGER,
    ADD COLUMN IF NOT EXISTS last_agg_used_count INTEGER,
    ADD COLUMN IF NOT EXISTS last_agg_outliers_removed INTEGER,
    ADD COLUMN IF NOT EXISTS last_agg_sigma DOUBLE PRECISION DEFAULT 2,
    ADD COLUMN IF NOT EXISTS last_agg_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_agg_filter_note TEXT;

-- 仅使用 HIGH/MEDIUM；排除手动标记无效；再按 param_a/param_b 各自总体标准差剔除 >2σ 的点
CREATE OR REPLACE FUNCTION public.update_device_profile(p_device_model TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_sigma CONSTANT DOUBLE PRECISION := 2;
    v_note CONSTANT TEXT := 'quality∈{HIGH,MEDIUM}; excluded_from_aggregation=false; outlier=per-axis |x-μ|≤2σ(pop on eligible set)';
    v_mean_a DOUBLE PRECISION;
    v_std_a DOUBLE PRECISION;
    v_mean_b DOUBLE PRECISION;
    v_std_b DOUBLE PRECISION;
    v_cnt INTEGER;
    v_fa DOUBLE PRECISION;
    v_fb DOUBLE PRECISION;
    v_samples INTEGER;
    v_conf DOUBLE PRECISION;
    v_updated BIGINT;
BEGIN
    SELECT
        AVG(param_a),
        COALESCE(STDDEV_POP(param_a), 0),
        AVG(param_b),
        COALESCE(STDDEV_POP(param_b), 0),
        COUNT(*)::INTEGER
    INTO v_mean_a, v_std_a, v_mean_b, v_std_b, v_cnt
    FROM public.calibration_records
    WHERE device_model = p_device_model
      AND quality IN ('HIGH', 'MEDIUM')
      AND NOT excluded_from_aggregation;

    IF v_cnt IS NULL OR v_cnt < 5 THEN
        RETURN;
    END IF;

    SELECT
        AVG(param_a),
        AVG(param_b),
        COUNT(*)::INTEGER
    INTO v_fa, v_fb, v_samples
    FROM public.calibration_records
    WHERE device_model = p_device_model
      AND quality IN ('HIGH', 'MEDIUM')
      AND NOT excluded_from_aggregation
      AND (v_std_a = 0 OR ABS(param_a - v_mean_a) <= v_sigma * v_std_a)
      AND (v_std_b = 0 OR ABS(param_b - v_mean_b) <= v_sigma * v_std_b);

    IF v_samples IS NULL OR v_samples < 3 THEN
        v_fa := v_mean_a;
        v_fb := v_mean_b;
        v_samples := v_cnt;
    END IF;

    v_conf := LEAST(1.0, v_samples::DOUBLE PRECISION / 50.0);
    v_updated := (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;

    INSERT INTO public.device_profiles (
        device_model,
        version,
        default_a,
        default_b,
        confidence,
        sample_count,
        updated_at,
        last_agg_eligible_count,
        last_agg_used_count,
        last_agg_outliers_removed,
        last_agg_sigma,
        last_agg_at,
        last_agg_filter_note
    )
    VALUES (
        p_device_model,
        1,
        v_fa,
        v_fb,
        v_conf,
        v_samples,
        v_updated,
        v_cnt,
        v_samples,
        GREATEST(0, v_cnt - v_samples),
        v_sigma,
        NOW(),
        v_note
    )
    ON CONFLICT (device_model) DO UPDATE SET
        version = public.device_profiles.version + 1,
        default_a = EXCLUDED.default_a,
        default_b = EXCLUDED.default_b,
        confidence = EXCLUDED.confidence,
        sample_count = EXCLUDED.sample_count,
        updated_at = EXCLUDED.updated_at,
        last_agg_eligible_count = EXCLUDED.last_agg_eligible_count,
        last_agg_used_count = EXCLUDED.last_agg_used_count,
        last_agg_outliers_removed = EXCLUDED.last_agg_outliers_removed,
        last_agg_sigma = EXCLUDED.last_agg_sigma,
        last_agg_at = EXCLUDED.last_agg_at,
        last_agg_filter_note = EXCLUDED.last_agg_filter_note;
END;
$$;

CREATE OR REPLACE FUNCTION public.update_all_device_profiles()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT DISTINCT device_model
        FROM public.calibration_records
        WHERE quality IN ('HIGH', 'MEDIUM')
          AND NOT excluded_from_aggregation
    LOOP
        PERFORM public.update_device_profile(r.device_model);
    END LOOP;
END;
$$;

-- 手动标记无效示例（在 SQL Editor 以 postgres 执行）：
-- UPDATE public.calibration_records
-- SET excluded_from_aggregation = TRUE, exclusion_note = '实验室重复上传'
-- WHERE upload_id = '...';
