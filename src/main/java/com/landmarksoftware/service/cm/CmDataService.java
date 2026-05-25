package com.landmarksoftware.service.cm;

import com.landmarksoftware.model.AppSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/**
 * Cash Management — cmbanks (bank accounts), cmtrans (cashbook transactions)
 * cmbanks PK: (company_no, bank_code). gl_acct_main_no links to glchart.
 * cmtrans: amt = transaction amount, system_id = source module, doc_type, doc_date
 * trx_status: O=outstanding (unreconciled), R=reconciled, V=void
 */
@Service
public class CmDataService {
    private static final Logger log = LoggerFactory.getLogger(CmDataService.class);
    private final JdbcTemplate jdbc;
    public CmDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int getBankCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM cmbanks WHERE company_no=? AND inactive_flag<>'Y'", Integer.class, s.getCompanyNo()); return v!=null?v:0; } catch (Exception e) { return 0; }
    }

    /** Total unreconciled items across all banks. */
    public int getUnreconciledCount(AppSession s) {
        try { Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM cmtrans WHERE company_no=? AND trx_status='O'", Integer.class, s.getCompanyNo()); return v!=null?v:0; } catch (Exception e) { return 0; }
    }

    /** Net cash position from GL balances for bank accounts. */
    public BigDecimal getCashPosition(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject(
            // Bank GL accounts joined via cmbanks; negate because bank accounts
            // are credit-normal in this GL (stored negative = positive cash)
            "SELECT COALESCE(SUM(-1 * (b.open_bal + b.bal_01+b.bal_02+b.bal_03+b.bal_04+b.bal_05+b.bal_06+b.bal_07+b.bal_08+b.bal_09+b.bal_10+b.bal_11+b.bal_12+b.bal_13)),0) " +
            "FROM glbal b JOIN cmbanks cb ON cb.company_no=b.company_no AND cb.gl_acct_main_no=b.acct_main_no " +
            "WHERE b.company_no=? AND b.year_no=? AND cb.inactive_flag<>'Y'",
            BigDecimal.class, s.getCompanyNo(), s.getYearNo()); return v!=null?v:BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Receipts YTD from cmtrans (cashbook_recpt_paymt='R'). */
    public BigDecimal getReceiptsYtd(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt),0) FROM cmtrans WHERE company_no=? AND cashbook_recpt_paymt='R' AND YEAR(doc_date)=?",
            BigDecimal.class, s.getCompanyNo(), s.getYearNo()); return v!=null?v:BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Payments YTD from cmtrans (cashbook_recpt_paymt='P'). */
    public BigDecimal getPaymentsYtd(AppSession s) {
        try { BigDecimal v = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amt),0) FROM cmtrans WHERE company_no=? AND cashbook_recpt_paymt='P' AND YEAR(doc_date)=?",
            BigDecimal.class, s.getCompanyNo(), s.getYearNo()); return v!=null?v:BigDecimal.ZERO;
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Cash flow by month — receipts vs payments. */
    public Map<String, Object> getCashFlowByMonth(AppSession s) {
        List<String> months = new ArrayList<>();
        List<BigDecimal> receipts = new ArrayList<>();
        List<BigDecimal> payments = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT DATE_FORMAT(doc_date,'%b %Y') AS month_label, YEAR(doc_date) AS yr, MONTH(doc_date) AS mo, " +
                "  COALESCE(SUM(CASE WHEN cashbook_recpt_paymt='R' THEN amt ELSE 0 END),0) AS rcpts, " +
                "  COALESCE(SUM(CASE WHEN cashbook_recpt_paymt='P' THEN amt ELSE 0 END),0) AS pmts " +
                "FROM cmtrans WHERE company_no=? AND YEAR(doc_date)=? " +
                "GROUP BY yr, mo, month_label ORDER BY yr, mo",
                rs -> { months.add(rs.getString("month_label")); receipts.add(z(rs.getBigDecimal("rcpts"))); payments.add(z(rs.getBigDecimal("pmts"))); },
                s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>(); r.put("months",months); r.put("receipts",receipts); r.put("payments",payments); return r;
    }

    /** Bank balances from glbal — one bar per bank. */
    public List<Map<String, Object>> getBankBalances(AppSession s) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT cb.name1, " +
                "  b.open_bal+b.bal_01+b.bal_02+b.bal_03+b.bal_04+b.bal_05+b.bal_06+b.bal_07+b.bal_08+b.bal_09+b.bal_10+b.bal_11+b.bal_12+b.bal_13 AS balance " +
                "FROM cmbanks cb " +
                "JOIN glbal b ON b.company_no=cb.company_no AND b.acct_main_no=cb.gl_acct_main_no " +
                "WHERE cb.company_no=? AND b.year_no=? AND cb.inactive_flag<>'Y' ORDER BY cb.name1",
                rs -> { Map<String,Object> item=new LinkedHashMap<>(); item.put("name",rs.getString("name1")); item.put("value",z(rs.getBigDecimal("balance"))); result.add(item); },
                s.getCompanyNo(), s.getYearNo());
        } catch (Exception e) { log.error("Query failed in {}: {}", getClass().getSimpleName(), e.getMessage()); }
        return result;
    }

    public Map<String, Object> getBankListingData(AppSession s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT cb.bank_code, cb.name1, cb.bank_acct_no, cb.branch_no, cb.contact_name, cb.phone_no, " +
                "  b.open_bal+b.bal_01+b.bal_02+b.bal_03+b.bal_04+b.bal_05+b.bal_06+b.bal_07+b.bal_08+b.bal_09+b.bal_10+b.bal_11+b.bal_12+b.bal_13 AS balance " +
                "FROM cmbanks cb " +
                "LEFT JOIN glbal b ON b.company_no=cb.company_no AND b.acct_main_no=cb.gl_acct_main_no AND b.year_no=? " +
                "WHERE cb.company_no=? AND cb.inactive_flag<>'Y' ORDER BY cb.name1",
                rs -> {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("code",rs.getString("bank_code")); row.put("name",rs.getString("name1"));
                    row.put("acctNo",rs.getString("bank_acct_no")); row.put("bsb",rs.getString("branch_no"));
                    row.put("contact",rs.getString("contact_name")); row.put("phone",rs.getString("phone_no"));
                    row.put("balance",rs.getBigDecimal("balance"));
                    rows.add(row);
                }, s.getYearNo(), s.getCompanyNo());
        } catch (Exception e) { return err(e.getMessage()); }
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("columns", List.of(col("Code","code","text"),col("Bank Name","name","text"),col("Account No","acctNo","text"),col("BSB","bsb","text"),col("Contact","contact","text"),col("Phone","phone","text"),col("Balance","balance","currency")));
        r.put("rows",rows); r.put("title","Bank Accounts"); return r;
    }

    private BigDecimal z(BigDecimal v){return v!=null?v:BigDecimal.ZERO;}
    private Map<String,Object> col(String l,String f,String t){return Map.of("label",l,"field",f,"type",t);}
    private Map<String,Object> err(String m){return Map.of("error",m,"columns",List.of(),"rows",List.of(),"title","Error");}
}
