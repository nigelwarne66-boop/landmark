-- ============================================================
-- Fixed Assets Migration — Support Tables
-- MariaDB / MySQL compatible DDL
--
-- Sources:
--   cp_company  ←  CPCOYCO Vision file (cpcoyco.fd)
--   gl_session  ←  GLPASS   Vision file (glpass.fd)
--
-- Only columns consumed by FATL12/FATL13 are included.
-- The full CPCOYCO record is enormous (AR/AP/GL/CM/PY/FA module
-- flags); the complete table should be extended to cover other
-- modules as they are migrated.
-- ============================================================

-- ------------------------------------------------------------
-- cp_company
--
-- COBOL source: cpcoyco.fd
-- Key: CPCOYCO-COMPANY-NO  PIC 9(3)
--
-- FA-module fields consumed by FATL12:
--   CPCOYCO-FA-TAX-YR-END-MTH  PIC 9(2)
--     Month number of the last month of the FA tax year.
--     1 = January year-end, 6 = June year-end (Australian standard),
--     12 = December year-end.
--
-- Representative other fields included for completeness.
-- Extend with full module data as other modules are migrated.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cp_company (

    -- ── Key ──────────────────────────────────────────────────
    company_no              SMALLINT        NOT NULL,          -- CPCOYCO-COMPANY-NO  PIC 9(3)

    -- ── Company identity ─────────────────────────────────────
    company_name            VARCHAR(50)     NOT NULL DEFAULT '',-- CPCOYCO-NAME
    company_name_2          VARCHAR(50)                 DEFAULT '',-- CPCOYCO-NAME-2
    addr_1                  VARCHAR(35)                 DEFAULT '',-- CPCOYCO-ADDR-1
    addr_2                  VARCHAR(35)                 DEFAULT '',-- CPCOYCO-ADDR-2
    addr_3                  VARCHAR(35)                 DEFAULT '',-- CPCOYCO-ADDR-3
    abn                     VARCHAR(22)                 DEFAULT '',-- CPCOYCO-ABN
    phone                   VARCHAR(20)                 DEFAULT '',-- CPCOYCO-PHONE
    website                 VARCHAR(60)                 DEFAULT '',-- CPCOYCO-WEBSITE

    -- ── Directory paths ───────────────────────────────────────
    -- Stored for reference; the Java app uses application.properties instead.
    data_files_dir          VARCHAR(200)                DEFAULT '',-- CPCOYCO-DATA-FILES-DIR
    work_files_dir          VARCHAR(200)                DEFAULT '',-- CPCOYCO-WORK-FILES-DIR
    print_files_dir         VARCHAR(200)                DEFAULT '',-- CPCOYCO-PRINT-FILES-DIR

    -- ── FA module config ─────────────────────────────────────
    fa_instal_flag          CHAR(1)         NOT NULL DEFAULT 'N',  -- CPCOYCO-FA-INSTAL-FLAG
    fa_last_batch_no        INT             NOT NULL DEFAULT 0,    -- CPCOYCO-FA-LAST-BATCH-NO  PIC 9(6)

    -- THE key FA field for FATL12 tax-stream projection:
    fa_tax_yr_end_mth       TINYINT         NOT NULL DEFAULT 6,    -- CPCOYCO-FA-TAX-YR-END-MTH  PIC 9(2)
    --   6  = Australian standard (year ends 30 June)
    --   12 = Calendar year (year ends 31 December)

    fa_gl_flag              CHAR(1)                     DEFAULT 'N',-- CPCOYCO-FA-GL-FLAG
    fa_det_summ_depn_ind    CHAR(1)                     DEFAULT 'D',-- CPCOYCO-FA-DET-SUMM-DEPN-IND
    fa_batch_control_flag   CHAR(1)                     DEFAULT 'N',-- CPCOYCO-FA-BATCH-CONTROL-FLAG

    -- ── Audit ─────────────────────────────────────────────────
    audit_user_id           VARCHAR(15)                 DEFAULT '',-- CPCOYCO-AUDIT-USER-ID
    audit_date              DATE,                                  -- CPCOYCO-AUDIT-DATE

    CONSTRAINT pk_cp_company PRIMARY KEY (company_no)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ------------------------------------------------------------
-- gl_session
--
-- COBOL source: glpass.fd
-- Key: GLPASS-TERMINAL-NO  PIC 9(3)
--
-- In the ACUCOBOL system, GLPASS is written at terminal login and
-- shared across all programs via a Vision file. In the Java
-- migration it can be populated from:
--   (a) This table (legacy parity, session written at login)
--   (b) Spring Security principal + CompanyRepository (recommended)
--
-- Fields consumed by FATL12:
--   GLPASS-COMPANY-NO        PIC 9(3)
--   GLPASS-YR-NO             PIC 9(2)  — 2-digit fiscal year
--   GLPASS-YEAR-NO           PIC 9(4)  — 4-digit fiscal year
--   GLPASS-BATCH-NO          PIC 9(6)
--   GLPASS-OPEN-BAL-DATE     PIC 9(6)
--   GLPASS-FA-TAX-YR-END-MTH PIC 9(2)  — copied from CPCOYCO at login
--
-- Fields consumed by FATL13:
--   GLPASS-COMPANY-NO        (to key FATLWK2)
--   GLPASS-FA-DET-SUMM-DEPN-IND  PIC X(1)
--   GLPASS-BOOK-OR-TAX-IND       PIC X(1)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gl_session (

    -- ── Key ──────────────────────────────────────────────────
    terminal_no             SMALLINT        NOT NULL,           -- GLPASS-TERMINAL-NO  PIC 9(3)

    -- ── Company & year ────────────────────────────────────────
    company_no              SMALLINT        NOT NULL DEFAULT 1,  -- GLPASS-COMPANY-NO   PIC 9(3)
    yr_no                   TINYINT         NOT NULL DEFAULT 0,  -- GLPASS-YR-NO        PIC 9(2)
    year_no                 SMALLINT        NOT NULL DEFAULT 0,  -- GLPASS-YEAR-NO      PIC 9(4)

    -- ── Batch ─────────────────────────────────────────────────
    batch_no                INT             NOT NULL DEFAULT 0,  -- GLPASS-BATCH-NO     PIC 9(6)

    -- ── FA-specific session values ────────────────────────────
    open_bal_date           DATE,                               -- GLPASS-OPEN-BAL-DATE  PIC 9(6) — YYMMDD in COBOL
    fa_tax_yr_end_mth       TINYINT         NOT NULL DEFAULT 6,  -- GLPASS-FA-TAX-YR-END-MTH  PIC 9(2)
    fa_det_summ_depn_ind    CHAR(1)                 DEFAULT 'D', -- GLPASS-FA-DET-SUMM-DEPN-IND
    book_or_tax_ind         CHAR(1)                 DEFAULT 'B', -- GLPASS-BOOK-OR-TAX-IND

    -- ── Additional common session fields ─────────────────────
    -- Included for completeness; extend as other modules are migrated.
    loc_no                  VARCHAR(4)              DEFAULT '',  -- GLPASS-LOC-NO
    user_id                 VARCHAR(15)             DEFAULT '',  -- GLPASS-USER-ID
    next_program_name       VARCHAR(10)             DEFAULT '',  -- GLPASS-NEXT-PROGRAM-NAME
    day_month_format        CHAR(1)                 DEFAULT 'D', -- GLPASS-DAY-MONTH-FORMAT
    yr_start_date           DATE,                               -- GLPASS-YR-START-DATE
    yr_end_date             DATE,                               -- GLPASS-YR-END-DATE

    -- ── Session lifecycle ─────────────────────────────────────
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_gl_session PRIMARY KEY (terminal_no),
    CONSTRAINT fk_gl_session_company
        FOREIGN KEY (company_no) REFERENCES cp_company (company_no)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ── Indexes ──────────────────────────────────────────────────
-- cp_company has no secondary indexes needed for FATL12.
-- gl_session: lookup by company for admin queries.
CREATE INDEX IF NOT EXISTS idx_gl_session_company
    ON gl_session (company_no);


-- ── Sample data (update to match your environment) ───────────
-- INSERT INTO cp_company (company_no, company_name, fa_tax_yr_end_mth)
-- VALUES (1, 'Acme Corporation', 6);   -- June year-end

-- INSERT INTO gl_session (terminal_no, company_no, yr_no, year_no, batch_no,
--                         open_bal_date, fa_tax_yr_end_mth)
-- VALUES (1, 1, 25, 2025, 1, '2025-07-01', 6);
