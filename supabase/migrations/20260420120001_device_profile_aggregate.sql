-- 聚合：按机型更新 device_profiles；剔除超过 2 倍总体标准差的异常点（对 param_a、param_b 分别过滤）
-- 由 SECURITY DEFINER 执行以绕过 device_profiles 上的 RLS 写限制。

CREATE OR REPLACE FUNCTION public.update_device_profile(p_device_model TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
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
      AND quality IN ('HIGH', 'MEDIUM');

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
      AND (v_std_a = 0 OR ABS(param_a - v_mean_a) <= 2 * v_std_a)
      AND (v_std_b = 0 OR ABS(param_b - v_mean_b) <= 2 * v_std_b);

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
        updated_at
    )
    VALUES (
        p_device_model,
        1,
        v_fa,
        v_fb,
        v_conf,
        v_samples,
        v_updated
    )
    ON CONFLICT (device_model) DO UPDATE SET
        version = public.device_profiles.version + 1,
        default_a = EXCLUDED.default_a,
        default_b = EXCLUDED.default_b,
        confidence = EXCLUDED.confidence,
        sample_count = EXCLUDED.sample_count,
        updated_at = EXCLUDED.updated_at;
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
    LOOP
        PERFORM public.update_device_profile(r.device_model);
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION public.update_device_profile(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_device_profile(TEXT) TO service_role;

REVOKE ALL ON FUNCTION public.update_all_device_profiles() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_all_device_profiles() TO service_role;

-- ---------------------------------------------------------------------------
-- 每日 UTC 0:00 聚合（需在 Dashboard → Database → Extensions 启用 pg_cron）
--
-- 启用后可在 SQL Editor 执行（重复执行前先按 jobid 取消旧任务）：
--
--   SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname = 'camera_aggregate_device_profiles';
--   SELECT cron.schedule(
--     'camera_aggregate_device_profiles',
--     '0 0 * * *',
--     'SELECT public.update_all_device_profiles()'
--   );
--
-- 若项目将 pg_cron 安装在非默认 schema，请按 Supabase 文档调整限定名。
-- ---------------------------------------------------------------------------
