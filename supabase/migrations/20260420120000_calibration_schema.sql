-- CameraProject · Supabase 标定与机型曲线
-- 在 Supabase Dashboard → SQL Editor 中执行，或使用 Supabase CLI link 后 db push。

-- ---------------------------------------------------------------------------
-- 表：标定记录（客户端仅 INSERT，anon 不可 SELECT 他人/全局原始行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.calibration_records (
    upload_id TEXT PRIMARY KEY,
    device_model TEXT NOT NULL,
    manufacturer TEXT,
    android_version TEXT,
    app_version TEXT,
    param_a REAL NOT NULL,
    param_b REAL NOT NULL,
    sample_count INTEGER NOT NULL,
    r_squared REAL NOT NULL,
    quality TEXT NOT NULL,
    calibration_time BIGINT,
    upload_time BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT calibration_quality_chk CHECK (quality IN ('HIGH', 'MEDIUM')),
    CONSTRAINT calibration_param_a_pos CHECK (param_a > 0),
    CONSTRAINT calibration_param_b_range CHECK (param_b >= 0.5 AND param_b <= 1.5),
    CONSTRAINT calibration_r2_range CHECK (r_squared >= 0 AND r_squared <= 1),
    CONSTRAINT calibration_sample_min CHECK (sample_count >= 5)
);

CREATE INDEX IF NOT EXISTS idx_calibration_device_model ON public.calibration_records (device_model);
CREATE INDEX IF NOT EXISTS idx_calibration_quality ON public.calibration_records (quality);
CREATE INDEX IF NOT EXISTS idx_calibration_created_at ON public.calibration_records (created_at);

-- ---------------------------------------------------------------------------
-- 表：设备标准曲线（客户端可读，写入仅通过聚合函数 / service_role）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_profiles (
    device_model TEXT PRIMARY KEY,
    version INTEGER NOT NULL DEFAULT 1,
    default_a REAL NOT NULL,
    default_b REAL NOT NULL,
    confidence REAL,
    sample_count INTEGER,
    updated_at BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
ALTER TABLE public.calibration_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_profiles ENABLE ROW LEVEL SECURITY;

-- 匿名（anon key）：只能插入标定记录，不能读取任意标定行
DROP POLICY IF EXISTS calibration_anon_insert ON public.calibration_records;
CREATE POLICY calibration_anon_insert
    ON public.calibration_records
    FOR INSERT
    TO anon
    WITH CHECK (true);

DROP POLICY IF EXISTS calibration_anon_select_deny ON public.calibration_records;
CREATE POLICY calibration_anon_select_deny
    ON public.calibration_records
    FOR SELECT
    TO anon
    USING (false);

-- 已登录用户若与 anon 策略相同，可按需放开；默认同样禁止读原始标定
DROP POLICY IF EXISTS calibration_authenticated_insert ON public.calibration_records;
CREATE POLICY calibration_authenticated_insert
    ON public.calibration_records
    FOR INSERT
    TO authenticated
    WITH CHECK (true);

DROP POLICY IF EXISTS calibration_authenticated_select_deny ON public.calibration_records;
CREATE POLICY calibration_authenticated_select_deny
    ON public.calibration_records
    FOR SELECT
    TO authenticated
    USING (false);

-- 聚合结果公开只读
DROP POLICY IF EXISTS device_profiles_anon_select ON public.device_profiles;
CREATE POLICY device_profiles_anon_select
    ON public.device_profiles
    FOR SELECT
    TO anon
    USING (true);

DROP POLICY IF EXISTS device_profiles_authenticated_select ON public.device_profiles;
CREATE POLICY device_profiles_authenticated_select
    ON public.device_profiles
    FOR SELECT
    TO authenticated
    USING (true);

-- ---------------------------------------------------------------------------
-- 最小权限 GRANT（RLS 仍生效）
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO anon, authenticated;

GRANT INSERT ON TABLE public.calibration_records TO anon, authenticated;
GRANT SELECT ON TABLE public.device_profiles TO anon, authenticated;

GRANT ALL ON TABLE public.calibration_records TO service_role;
GRANT ALL ON TABLE public.device_profiles TO service_role;
