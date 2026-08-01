DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audiobook_inspection') THEN
        CREATE ROLE audiobook_inspection LOGIN PASSWORD 'inspection-local';
    ELSE
        ALTER ROLE audiobook_inspection WITH LOGIN PASSWORD 'inspection-local';
    END IF;
END
$$;
