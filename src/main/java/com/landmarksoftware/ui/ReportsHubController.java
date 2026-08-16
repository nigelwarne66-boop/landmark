package com.landmarksoftware.ui;

import com.landmarksoftware.desktop.AppMode;
import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.report.JasperReportService;
import com.landmarksoftware.report.ModuleDef;
import com.landmarksoftware.report.ReportDef;
import com.landmarksoftware.report.ReportFavouritesStore;
import com.landmarksoftware.service.CpCntrlService;
import com.landmarksoftware.export.DepreciationPdfService;
import com.landmarksoftware.export.DepreciationExportService;
import com.landmarksoftware.export.AcquiredRetiredPdfService;
import com.landmarksoftware.export.AcquiredRetiredExportService;
import com.landmarksoftware.export.TransactionListPdfService;
import com.landmarksoftware.export.TransactionListExportService;
import com.landmarksoftware.export.EmployeePdfService;
import com.landmarksoftware.report.AssetRegisterViewerService;
import com.landmarksoftware.ui.nav.Module;
import com.landmarksoftware.ui.shell.AppShell;
import com.landmarksoftware.ui.shell.ShellContext;
import com.landmarksoftware.ui.shell.ShellHeader;
import com.landmarksoftware.ui.shell.ShellRail;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.*;

@Component
public class ReportsHubController implements Initializable {

    /* ── FXML ──────────────────────────────────────────────────── */
    @FXML private StackPane headerSlot;
    @FXML private StackPane railSlot;
    @FXML private TextField searchField;
    @FXML private Label     moduleTitle;
    @FXML private Label     reportCount;
    @FXML private VBox      reportList;
    @FXML private Label     emptyLabel;

    /* ── Shared shell (DESIGN_SYSTEM.md §5) — built at initialize(), kept
       so a company switch or favourites toggle can refresh in place. ──── */
    private ShellHeader shellHeader;
    private ShellRail   shellRail;

    /* ── Spring ────────────────────────────────────────────────── */
    @Autowired private AppSession                   session;
    @Autowired private ReportFavouritesStore        favStore;
    @Autowired private JasperReportService          jasper;
    @Autowired private ApplicationContext           springContext;
    @Autowired private CpCntrlService               cpCntrl;
    @Autowired private AppShell                     appShell;
    // Used only to spawn MENU23 (Switch Company) — same dialog code as the full app.
    @Autowired private MainMenuController           mainMenu;
    // Injected for future selection-screen wiring — runners below stub to
    // comingSoon() because actual service APIs don't take (AppSession).
    @Autowired private AssetRegisterViewerService   assetRegisterSvc;
    @Autowired private DepreciationPdfService       depreciationPdf;
    @Autowired private DepreciationExportService    depreciationXls;
    @Autowired private AcquiredRetiredPdfService    acquiredPdf;
    @Autowired private AcquiredRetiredExportService acquiredXls;
    @Autowired private TransactionListPdfService    txnListPdf;
    @Autowired private TransactionListExportService txnListXls;
    @Autowired private EmployeePdfService           employeePdf;

    /* ── State ─────────────────────────────────────────────────── */
    private List<ModuleDef> modules;
    private ModuleDef       activeModule;

    /* ── Colour map — report-row icon tiles only; rail icons come from the
       Module enum via the shared shell. ──────────────────────────────── */
    private static final Map<String, String> MODULE_STYLE = Map.ofEntries(
        Map.entry("fa", "icon-fa"), Map.entry("gl", "icon-gl"), Map.entry("py", "icon-py"),
        Map.entry("ar", "icon-ar"), Map.entry("ap", "icon-ap"), Map.entry("cm", "icon-cm"),
        Map.entry("bas", "icon-ap"), Map.entry("po", "icon-ap"), Map.entry("sm", "icon-cm"),
        Map.entry("fav", "icon-fav")
    );

    /* ── Init ──────────────────────────────────────────────────── */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        buildModuleRegistry();

        ShellContext ctx = buildShellContext();
        shellHeader = appShell.buildHeader(ctx);
        shellRail   = appShell.buildRail(ctx);
        headerSlot.getChildren().setAll(shellHeader.getNode());
        railSlot.getChildren().setAll(shellRail.getNode());

        selectModule(modules.isEmpty() ? null : modules.get(0));
        searchField.textProperty().addListener((obs, old, val) -> filterReports(val));
    }

    /** Config shared by the header + rail — DESIGN_SYSTEM.md §5. Unlike
     *  MainMenuController, this app already has a working per-report
     *  favourites feature (ReportFavouritesStore), so the shared shell's
     *  Favourites row is wired up here instead of left as a placeholder. */
    private ShellContext buildShellContext() {
        String displayName = session.getUserName();
        if (displayName == null || displayName.isBlank()) displayName = session.getUserId();
        String secondary = session.getUserId();
        if (secondary != null && secondary.equals(displayName)) secondary = null;

        return new ShellContext()
            .companyName(session.getCompanyName())
            .financialYearLabel(session.getYearDesc())
            .onContextChipClick(this::openCompanyYearSwitcher)
            .userDisplayName(displayName)
            .userSecondaryLine(secondary)
            .onSwitchCompany(this::openCompanyYearSwitcher)
            .onSwitchFinancialYear(this::openCompanyYearSwitcher)
            .onPreferences(() -> comingSoon("Preferences"))
            .onSignOut(this::onSignOut)
            .activeModule(null)
            .moduleVisible(m -> !"sys".equals(m.getRouteId()) && session.isModuleInstalled(m.getRouteId()))
            .onModuleSelected(this::selectModuleByRoute)
            .onFavouritesClick(() -> selectModule(null))
            .favouritesCount(favStore::count);
    }

    /** Refresh the chip + user name after a MENU23 switch, without a full
     *  header rebuild. */
    private void refreshHeader() {
        if (shellHeader == null) return;
        shellHeader.updateContext(ShellContext.chipText(session.getCompanyName(), session.getYearDesc()));
        String displayName = session.getUserName();
        if (displayName == null || displayName.isBlank()) displayName = session.getUserId();
        shellHeader.updateUserName(displayName);
    }

    /** {@link Module#byRouteId(String)} bridge for {@link ShellContext#onModuleSelected}. */
    private void selectModuleByRoute(Module m) {
        modules.stream()
            .filter(mod -> mod.getId().equals(m.getRouteId()))
            .findFirst()
            .ifPresent(this::selectModule);
    }

    /* ── Module registry ───────────────────────────────────────── */
    private void buildModuleRegistry() {

        /* Fixed Assets */
        ReportDef assetRegister = ReportDef.withParams(
            "asset-register", "Asset Register",
            "Full asset listing by group and location",
            "fth-package");
        assetRegister.setRunner(fmt -> comingSoon("Asset Register"));

        ReportDef depreciation = ReportDef.withParams(
            "depreciation", "Depreciation",
            "Depreciation charges by period",
            "fth-trending-down");
        depreciation.setRunner(fmt -> comingSoon("Depreciation"));

        ReportDef acquiredRetired = ReportDef.withParams(
            "acquired-retired", "Acquired & Retired",
            "Assets acquired or retired in the year",
            "fth-repeat");
        acquiredRetired.setRunner(fmt -> comingSoon("Acquired & Retired"));

        ReportDef txnList = ReportDef.withParams(
            "transaction-list", "Transaction List",
            "All asset transactions for the year",
            "fth-list");
        txnList.setRunner(fmt -> comingSoon("Transaction List"));

        /* Payroll */
        ReportDef payrollSummary = ReportDef.withParams(
            "payroll-summary", "Payroll Summary",
            "Gross, tax, super and net by pay run",
            "fth-dollar-sign");
        payrollSummary.setRunner(fmt -> comingSoon("Payroll Summary"));

        ReportDef employeeList = ReportDef.withParams(
            "employee-list", "Employee List",
            "All staff with rate and current status",
            "fth-user");
        employeeList.setRunner(fmt -> comingSoon("Employee List"));

        ReportDef ytdPayments = ReportDef.withParams(
            "employee-ytd-payments", "Employee YTD Payments",
            "Year-to-date payment amounts by pay code per employee",
            "fth-dollar-sign");
        ytdPayments.setRunner(fmt -> comingSoon("Employee YTD Payments"));

        ReportDef histDetail = ReportDef.withParams(
            "employee-history-detail", "Employee History Detail",
            "Full posted payroll history per employee (PATL14)",
            "fth-file-text");
        histDetail.setRunner(fmt -> comingSoon("Employee History Detail"));

        ReportDef histSummary = ReportDef.withParams(
            "employee-history-summary", "Employee History Summary",
            "Summarised payroll history with sort option (PATL17/30/55)",
            "fth-bar-chart-2");
        histSummary.setRunner(fmt -> comingSoon("Employee History Summary"));

        ReportDef dednSuper = ReportDef.withParams(
            "deductions-super", "Deductions & Superannuation",
            "YTD deductions and super by pay code or fund (PATL05/09)",
            "fth-dollar-sign");
        dednSuper.setRunner(fmt -> comingSoon("Deductions & Superannuation"));

        ReportDef deptExpenses = ReportDef.withParams(
            "dept-expenses", "Department Expenses",
            "Payroll cost distribution by department for a period (PATL16)",
            "fth-bar-chart-2");
        deptExpenses.setRunner(fmt -> comingSoon("Department Expenses"));

        ReportDef payPeriodSummary = ReportDef.withParams(
            "period-summary", "Period Summary",
            "Payroll totals by type for each pay run (PATL07)",
            "fth-bar-chart-2");
        payPeriodSummary.setRunner(fmt -> comingSoon("Period Summary"));

        ReportDef payrunGlDetail = ReportDef.withParams(
            "payrun-gl-detail", "Payrun GL Detail",
            "Full GL line detail for a single posted payrun (PATL60)",
            "fth-file-text");
        payrunGlDetail.setRunner(fmt -> comingSoon("Payrun GL Detail"));

        ReportDef timesheetHist = ReportDef.withParams(
            "timesheet-history", "Timesheet History",
            "Payroll history by paygroup and employee (PATL28)",
            "fth-file-text");
        timesheetHist.setRunner(fmt -> comingSoon("Timesheet History"));

        ReportDef dednStatus = ReportDef.withParams(
            "super-deductions-status", "Super/Deductions Status",
            "Deduction and super payment status by pay code (PATL40)",
            "fth-dollar-sign");
        dednStatus.setRunner(fmt -> comingSoon("Super/Deductions Status"));

        ReportDef superByFund = ReportDef.withParams(
            "super-by-fund", "Super by Fund",
            "YTD super contributions grouped by fund (PASP10)",
            "fth-dollar-sign");
        superByFund.setRunner(fmt -> comingSoon("Super by Fund"));

        ReportDef extendedSuper = ReportDef.withParams(
            "extended-super", "Extended Superannuation",
            "Detailed super history with fund details and masked TFN (PATL26)",
            "fth-dollar-sign");
        extendedSuper.setRunner(fmt -> comingSoon("Extended Superannuation"));

        /* General Ledger */
        ReportDef trialBalance = ReportDef.withParams(
            "trial-balance", "Trial Balance",
            "Account balances for a period range",
            "fth-bar-chart-2");
        trialBalance.setRunner(fmt -> comingSoon("Trial Balance"));

        ReportDef profitLoss = ReportDef.withParams(
            "profit-loss", "Profit & Loss",
            "Income vs expenses summary",
            "fth-trending-up");
        profitLoss.setRunner(fmt -> comingSoon("Profit & Loss"));

        ReportDef balanceSheet = ReportDef.withParams(
            "balance-sheet", "Balance Sheet",
            "Assets, liabilities and equity at a date",
            "fth-credit-card");
        balanceSheet.setRunner(fmt -> comingSoon("Balance Sheet"));

        ReportDef generalJournal = ReportDef.withParams(
            "general-journal", "General Journal",
            "All posted journal entries",
            "fth-book");
        generalJournal.setRunner(fmt -> comingSoon("General Journal"));

        ReportDef acctTxns = ReportDef.withParams(
            "account-transactions", "Account Transactions",
            "Drilldown transactions for one account",
            "fth-file-text");
        acctTxns.setRunner(fmt -> comingSoon("Account Transactions"));

        // GLRP40 — Report Writer dispatcher. FXML at /fxml/reports/gl/report-writer.fxml
        // drives GlReportWriterController + GlReportWriterService (matrix evaluator);
        // setRunner is only the fallback the hub falls through to if the FXML fails.
        ReportDef glReportWriter = ReportDef.withParams(
            "report-writer", "Report Writer Output",
            "Run user-defined report-writer reports — pick from the saved definitions",
            "fth-edit-3");
        glReportWriter.setRunner(fmt -> comingSoon("Report Writer Output"));

        /* Accounts Receivable */
        ReportDef arTransactionInquiry = ReportDef.withParams(
            "transaction-inquiry", "Transaction Inquiry",
            "AR customer transaction inquiry — browse transactions with drill-down to distributions",
            "fth-search");

        ReportDef debtorsAgeing = ReportDef.withParams(
            "debtors-ageing", "Debtors Ageing",
            "Customer balances aged across 4 configurable periods (ARTL32)",
            "fth-users");
        debtorsAgeing.setRunner(fmt -> comingSoon("Debtors Ageing"));

        ReportDef arTransactionListing = ReportDef.withParams(
            "transaction-listing", "Transaction Listing",
            "AR transactions by customer, with optional distribution lines (ARRC05)",
            "fth-list");
        arTransactionListing.setRunner(fmt -> comingSoon("Transaction Listing"));

        ReportDef salesDistribution = ReportDef.withParams(
            "sales-distribution", "Sales Distribution",
            "MTD/YTD sales by sub-ledger and sales code, this year vs last year (ARTL10)",
            "fth-trending-up");
        salesDistribution.setRunner(fmt -> comingSoon("Sales Distribution"));

        ReportDef salesByGl = ReportDef.withParams(
            "sales-by-gl", "Sales by GL",
            "MTD/YTD sales by GL account, this year vs last year (ARTL18)",
            "fth-bar-chart-2");
        salesByGl.setRunner(fmt -> comingSoon("Sales by GL"));

        ReportDef arAccountRecon = ReportDef.withParams(
            "account-reconciliation", "Account Reconciliation",
            "Reconciliation detail by customer — gross / net per transaction (ARRC03)",
            "fth-check-square");
        arAccountRecon.setRunner(fmt -> comingSoon("Account Reconciliation"));

        ReportDef arUnbalancedRecon = ReportDef.withParams(
            "unbalanced-reconciliation", "Unbalanced Reconciliation",
            "Reconciliations out of balance, by customer (ARRC04)",
            "fth-alert-triangle");
        arUnbalancedRecon.setRunner(fmt -> comingSoon("Unbalanced Reconciliation"));

        ReportDef arDetailedTxn = ReportDef.withParams(
            "detailed-transaction-listing", "Detailed Transaction Listing",
            "AR distribution lines per transaction, with tax detail (ARRC09)",
            "fth-file-text");
        arDetailedTxn.setRunner(fmt -> comingSoon("Detailed Transaction Listing"));

        ReportDef arFcReval = ReportDef.withParams(
            "fc-revaluation", "Foreign Currency Revaluation",
            "FC transactions with revaluation adjustments (ARRC11)",
            "fth-refresh-cw");
        arFcReval.setRunner(fmt -> comingSoon("Foreign Currency Revaluation"));

        ReportDef arGlDistribution = ReportDef.withParams(
            "gl-distribution", "GL Distribution",
            "AR GL postings by account, control vs sales, for a period (ARTL02)",
            "fth-pie-chart");
        arGlDistribution.setRunner(fmt -> comingSoon("GL Distribution"));

        ReportDef arPeriodSummary = ReportDef.withParams(
            "period-summary", "Period Summary",
            "Opening, movements and closing per AR sub ledger for a period (ARTL03)",
            "fth-calendar");
        arPeriodSummary.setRunner(fmt -> comingSoon("Period Summary"));

        ReportDef arDocumentNumber = ReportDef.withParams(
            "document-number", "Document Number",
            "AR document register — invoices, credit and debit notes (ARTL20)",
            "fth-file");
        arDocumentNumber.setRunner(fmt -> comingSoon("Document Number"));

        ReportDef arAdjustmentNote = ReportDef.withParams(
            "adjustment-note-analysis", "Adjustment Note Analysis",
            "Credit and debit notes over a date range (ARTL22)",
            "fth-file-text");
        arAdjustmentNote.setRunner(fmt -> comingSoon("Adjustment Note Analysis"));

        ReportDef arDebtorsControl = ReportDef.withParams(
            "debtors-control", "Debtors Control",
            "Customer balances and period sales, sortable with top-N (ARTL11)",
            "fth-activity");
        arDebtorsControl.setRunner(fmt -> comingSoon("Debtors Control"));

        ReportDef arCustomerAcctStatus = ReportDef.withParams(
            "customer-account-status", "Customer Account Status",
            "Customers by account status — active / no sales / on hold / inactive (ARTL21)",
            "fth-user");
        arCustomerAcctStatus.setRunner(fmt -> comingSoon("Customer Account Status"));

        ReportDef arCustomerSalesByType = ReportDef.withParams(
            "customer-sales-by-type", "Customer Sales by Type",
            "Customer YTD sales / cost / profit grouped by customer type (ARTL06)",
            "fth-bar-chart-2");
        arCustomerSalesByType.setRunner(fmt -> comingSoon("Customer Sales by Type"));

        ReportDef arCustomerSalesBySubLedger = ReportDef.withParams(
            "customer-sales-by-subledger", "Customer Sales by Sub Ledger",
            "Customer YTD sales / cost / profit grouped by sub ledger (ARTL27)",
            "fth-bar-chart-2");
        arCustomerSalesBySubLedger.setRunner(fmt -> comingSoon("Customer Sales by Sub Ledger"));

        ReportDef arSalesBySalesperson = ReportDef.withParams(
            "sales-by-salesperson", "Customer Sales by Salesperson",
            "Customers grouped by salesperson, MTD + YTD sales (ARTL15)",
            "fth-users");
        arSalesBySalesperson.setRunner(fmt -> comingSoon("Customer Sales by Salesperson"));

        ReportDef arSalespersonProfit = ReportDef.withParams(
            "salesperson-profitability", "Salesperson Profitability",
            "Sales, cost and gross margin by salesperson (ARTL16)",
            "fth-trending-up");
        arSalespersonProfit.setRunner(fmt -> comingSoon("Salesperson Profitability"));

        ReportDef arSalesJournal = ReportDef.withParams(
            "sales-journal", "Sales Journal",
            "Invoices, debit and credit notes by sub ledger over a date range (ARTL05)",
            "fth-book");
        arSalesJournal.setRunner(fmt -> comingSoon("Sales Journal"));

        ReportDef arCommission = ReportDef.withParams(
            "commission", "Commission",
            "Per-transaction commission by salesperson (ARTL04)",
            "fth-dollar-sign");
        arCommission.setRunner(fmt -> comingSoon("Commission"));

        ReportDef arCustomerSalesByYear = ReportDef.withParams(
            "customer-sales-by-year", "Customer Sales by Year",
            "Five trailing-year sales totals per customer (SMTL38)",
            "fth-bar-chart-2");
        arCustomerSalesByYear.setRunner(fmt -> comingSoon("Customer Sales by Year"));

        /* Accounts Payable */
        ReportDef transactionInquiry = ReportDef.withParams(
            "transaction-inquiry", "Transaction Inquiry",
            "AP supplier transaction inquiry — browse transactions with drill-down to distributions",
            "fth-search");

        ReportDef creditorsAgeing = ReportDef.withParams(
            "creditors-ageing", "Creditors Ageing",
            "Supplier balances aged across 6 monthly buckets",
            "fth-users");
        creditorsAgeing.setRunner(fmt -> comingSoon("Creditors Ageing"));

        ReportDef transactionListing = ReportDef.withParams(
            "transaction-listing", "Transaction Listing",
            "AP transactions by supplier, with optional distribution lines",
            "fth-list");
        transactionListing.setRunner(fmt -> comingSoon("Transaction Listing"));

        ReportDef detailedTxnListing = ReportDef.withParams(
            "detailed-transaction-listing", "Detailed Transaction Listing",
            "AP transactions with full GL distribution detail and tax codes",
            "fth-file-text");
        detailedTxnListing.setRunner(fmt -> comingSoon("Detailed Transaction Listing"));

        ReportDef periodSummary = ReportDef.withParams(
            "period-summary", "Period Summary",
            "Opening, movements and closing per AP sub ledger for a period",
            "fth-calendar");
        periodSummary.setRunner(fmt -> comingSoon("Period Summary"));

        ReportDef glDistributions = ReportDef.withParams(
            "gl-distributions", "GL Distributions Summary",
            "AP GL postings by account, control vs expense, for a period",
            "fth-pie-chart");
        glDistributions.setRunner(fmt -> comingSoon("GL Distributions Summary"));

        ReportDef purchaseHistory = ReportDef.withParams(
            "purchase-history", "Supplier Purchase History",
            "Period and YTD purchases per supplier, this year vs last year",
            "fth-trending-up");
        purchaseHistory.setRunner(fmt -> comingSoon("Supplier Purchase History"));

        ReportDef unbalancedRecon = ReportDef.withParams(
            "unbalanced-reconciliation", "Unbalanced Reconciliation",
            "Suppliers/reconciliations out of balance in local or foreign currency",
            "fth-alert-triangle");
        unbalancedRecon.setRunner(fmt -> comingSoon("Unbalanced Reconciliation"));

        ReportDef accountRecon = ReportDef.withParams(
            "account-reconciliation", "Account Reconciliation",
            "Reconciliation detail — invoices matched to payments",
            "fth-check-square");
        accountRecon.setRunner(fmt -> comingSoon("Account Reconciliation"));

        ReportDef cashRequirements = ReportDef.withParams(
            "cash-requirements", "Cash Requirements",
            "Outstanding transactions due for payment, allocated by rule",
            "fth-dollar-sign");
        cashRequirements.setRunner(fmt -> comingSoon("Cash Requirements"));

        ReportDef supplierAnalysis = ReportDef.withParams(
            "supplier-analysis", "Supplier Analysis",
            "N-month transaction analysis by supplier (Excel) with top-N ranking",
            "fth-activity");
        supplierAnalysis.setRunner(fmt -> comingSoon("Supplier Analysis"));

        ReportDef fcRevaluation = ReportDef.withParams(
            "fc-revaluation", "Foreign Currency Revaluation",
            "FC transactions with revaluation gain/loss (FC companies only)",
            "fth-refresh-cw");
        fcRevaluation.setRunner(fmt -> comingSoon("Foreign Currency Revaluation"));

        /* Cash Management (cashbook) */
        ReportDef cmCashbookTransactions = ReportDef.withParams(
            "cashbook-transactions", "Cashbook Transactions",
            "Cashbook transactions for a bank, with reconciliation filter (CMTL10)",
            "fth-list");
        cmCashbookTransactions.setRunner(fmt -> comingSoon("Cashbook Transactions"));

        ReportDef cmCashbookListing = ReportDef.withParams(
            "cashbook-listing", "Cashbook Listing",
            "Cashbook documents by bank, receipts / payments (CMCB02)",
            "fth-file-text");
        cmCashbookListing.setRunner(fmt -> comingSoon("Cashbook Listing"));

        ReportDef cmCashbookByType = ReportDef.withParams(
            "cashbook-by-type", "Cashbook by Type",
            "Cashbook transactions grouped by document type (CMTL35)",
            "fth-bar-chart-2");
        cmCashbookByType.setRunner(fmt -> comingSoon("Cashbook by Type"));

        ReportDef cmCashbookDistributions = ReportDef.withParams(
            "cashbook-distributions", "Cashbook Distributions",
            "Cashbook distribution lines by GL account and tax code (CMTL14)",
            "fth-pie-chart");
        cmCashbookDistributions.setRunner(fmt -> comingSoon("Cashbook Distributions"));

        ReportDef cmCashbookLedger = ReportDef.withParams(
            "cashbook-ledger", "Cashbook Ledger",
            "Cashbook ledger with opening and running balance (CMTL05)",
            "fth-book");
        cmCashbookLedger.setRunner(fmt -> comingSoon("Cashbook Ledger"));

        ReportDef cmDocumentListing = ReportDef.withParams(
            "document-listing", "Document Listing",
            "Cashbook documents with amount paid and outstanding (CMTL18)",
            "fth-list");
        cmDocumentListing.setRunner(fmt -> comingSoon("Document Listing"));

        ReportDef cmBankReconciliation = ReportDef.withParams(
            "bank-reconciliation", "Bank Reconciliation Statement",
            "Reconciliation balances and the transactions reconciled (CMTL02)",
            "fth-check-square");
        cmBankReconciliation.setRunner(fmt -> comingSoon("Bank Reconciliation Statement"));

        ReportDef cmReceiptListing = ReportDef.withParams(
            "receipt-listing", "Receipt Listing",
            "Cashbook receipts by bank, with receipt type and status (CMTL08)",
            "fth-dollar-sign");
        cmReceiptListing.setRunner(fmt -> comingSoon("Receipt Listing"));

        ReportDef cmFcMatch = ReportDef.withParams(
            "fc-match", "Cashbook FC Match",
            "Foreign-currency cashbook transactions and rates (CMTL30)",
            "fth-refresh-cw");
        cmFcMatch.setRunner(fmt -> comingSoon("Cashbook FC Match"));

        /* Purchasing (PO) */
        ReportDef poInSequence = ReportDef.withParams(
            "orders-in-sequence", "Purchase Orders in Sequence",
            "PO list in a chosen sort sequence — order/supplier/item/date/GL/ledger (POTL22)",
            "fth-list");
        poInSequence.setRunner(fmt -> comingSoon("Purchase Orders in Sequence"));

        ReportDef poSummary = ReportDef.withParams(
            "po-summary", "Purchase Order Summary",
            "One row per PO: ordered, invoiced, delivered and outstanding values (POTL20)",
            "fth-file-text");

        ReportDef poDetail = ReportDef.withParams(
            "po-detail", "Purchase Order Detail",
            "One row per PO line: quantities and values grouped by PO (POTL21)",
            "fth-align-justify");

        ReportDef purchaseIndex = ReportDef.withParams(
            "purchase-index", "Purchase Index",
            "Per-line ordered/delivered/invoiced values with AP document count (POTL33)",
            "fth-search");

        ReportDef poVariance = ReportDef.withParams(
            "delivery-invoice-variance", "Delivery / Invoice Variance",
            "Per-line delivered value vs invoiced value and variance (POTL28)",
            "fth-alert-triangle");

        ReportDef poUninvoicedGoods = ReportDef.withParams(
            "uninvoiced-goods", "Uninvoiced Goods Reconcile",
            "Received-not-invoiced value per PO line, stock goods (POTL36)",
            "fth-truck");
        ReportDef poUninvoicedSundries = ReportDef.withParams(
            "uninvoiced-sundries", "Uninvoiced Goods (Sundries)",
            "Received-not-invoiced value for sundry / non-stock lines (POTL37)",
            "fth-truck");
        ReportDef poSundriesRecon = ReportDef.withParams(
            "sundries-reconcile", "Sundries Reconcile",
            "AP purchase documents — matched value and adjustments (POTL39)",
            "fth-check-square");
        ReportDef poExpedite = ReportDef.withParams(
            "expedite-action", "Expedite Action",
            "Overdue / undelivered PO lines due in a window (POTL30)",
            "fth-clock");

        /* SM — Inventory Management */
        ReportDef smStockItemInquiry = ReportDef.withParams(
            "stock-item-inquiry", "Stock Item Inquiry",
            "Item across all locations — quantity and value on hand, with movement drill-down",
            "fth-search");
        ReportDef smStockAvailability = ReportDef.withParams(
            "stock-availability", "Stock Availability Inquiry",
            "Item quantity position across locations — on hand, allocated, available, on order",
            "fth-layers");

        ReportDef smMovementsDetail = ReportDef.withParams(
            "inventory-movements-detail", "Inventory Movements Detail",
            "Every stock movement — receipts, sales, adjustments, transfers (SMTL01)",
            "fth-activity");
        ReportDef smMovementsSummary = ReportDef.withParams(
            "inventory-movements-summary", "Inventory Movements Summary",
            "Net quantity in / out and value per item (SMTL02)",
            "fth-bar-chart-2");
        ReportDef smValuation = ReportDef.withParams(
            "inventory-valuation", "Inventory Valuation",
            "Stock on hand at cost by location and item (SMTL07)",
            "fth-dollar-sign");
        ReportDef smAvailability = ReportDef.withParams(
            "item-availability", "Item Availability",
            "On hand, allocated, on order and available quantities (SMTL26)",
            "fth-check-circle");
        ReportDef smReorder = ReportDef.withParams(
            "reorder-requisitions", "Reorder & PO Requisitions",
            "Items below minimum level with suggested reorder qty (SMTL10)",
            "fth-shopping-cart");
        ReportDef smInactive = ReportDef.withParams(
            "inactive-inventory", "Inactive Inventory",
            "Items with no movement / sale since a date (SMTL20)",
            "fth-pause-circle");
        ReportDef smItemStatus = ReportDef.withParams(
            "item-status", "Item Status",
            "One list, sort by Item or Location (SMTL15 + SMTL24)",
            "fth-info");
        ReportDef smSerialBatch = ReportDef.withParams(
            "serial-batch-history", "Serial / Batch History",
            "Movement history by serial / batch number (SMTL27)",
            "fth-hash");
        ReportDef smConsignGl = ReportDef.withParams(
            "consignment-gl-reconcile", "Consignment Stock GL Reconcile",
            "Consignment stock on hand at cost (SMTL36)",
            "fth-git-merge");
        ReportDef smConsignStock = ReportDef.withParams(
            "consignment-stock", "Consignment Stock",
            "One list, sort by Customer or Item (SMTL53 + SMTL56)",
            "fth-share-2");
        ReportDef smSalesHistory = ReportDef.withParams(
            "sales-history", "Sales History",
            "Sales by item and customer with margin (SMTL06)",
            "fth-trending-up");
        ReportDef smTxnByCustomer = ReportDef.withParams(
            "transactions-by-customer", "Transactions by Customer",
            "Stock movements grouped by customer (SMTL16)",
            "fth-users");
        ReportDef smPurchaseAnalysis = ReportDef.withParams(
            "purchase-analysis", "Purchase Analysis",
            "Purchase receipts by item and supplier (SMTL12)",
            "fth-trending-down");
        ReportDef smPriceList = ReportDef.withParams(
            "price-list", "Price List",
            "Recommended and wholesale prices by item (SMTL08)",
            "fth-tag");

        /* BAS — Business Activity Statement */
        ReportDef basStatement = ReportDef.withParams(
            "business-activity-statement", "Business Activity Statement",
            "GST, PAYG and FBT summary for a BAS period (CPBA12)",
            "fth-percent");
        ReportDef detailedBas = ReportDef.withParams(
            "detailed-bas", "Detailed BAS",
            "BAS transactions by code — summary or full detail (CPBA13)",
            "fth-list");
        ReportDef basTransactions = ReportDef.withParams(
            "bas-transactions", "BAS Transactions",
            "Filtered BAS transaction listing with date range and GST code (CPBA06)",
            "fth-file-text");
        ReportDef basByGl = ReportDef.withParams(
            "bas-by-gl", "BAS by GL Account",
            "BAS amounts aggregated by GL clearing account and BAS code (CPBA16)",
            "fth-bar-chart-2");

        // Sidebar order: GL, AR, AP, CM, PO, SM, FA, Payroll, BAS.
        // Each module is gated on cpcoyco install flag via session.isModuleInstalled().
        // Payroll additionally requires MEUSERS.print_pa_from_pass='Y' (isPayrollAccess).
        java.util.List<ModuleDef> mods = new java.util.ArrayList<>();
        if (session.isModuleInstalled("gl"))
            mods.add(new ModuleDef("gl", "General Ledger",
                List.of(trialBalance, profitLoss, balanceSheet, generalJournal, acctTxns,
                        glReportWriter)));
        if (session.isModuleInstalled("ar"))
            mods.add(new ModuleDef("ar", "Accounts Receivable",
                List.of(arTransactionInquiry, debtorsAgeing, arTransactionListing, arAccountRecon, arUnbalancedRecon,
                        arDetailedTxn, arFcReval, arGlDistribution, arPeriodSummary,
                        arDocumentNumber, arAdjustmentNote, salesDistribution, salesByGl,
                        arDebtorsControl, arCustomerAcctStatus, arCustomerSalesByType,
                        arCustomerSalesBySubLedger, arSalesBySalesperson, arSalespersonProfit,
                        arSalesJournal, arCommission, arCustomerSalesByYear)));
        if (session.isModuleInstalled("ap"))
            mods.add(new ModuleDef("ap", "Accounts Payable",
                List.of(transactionInquiry, creditorsAgeing, transactionListing, detailedTxnListing,
                        periodSummary, glDistributions, purchaseHistory,
                        unbalancedRecon, accountRecon, cashRequirements,
                        supplierAnalysis, fcRevaluation)));
        if (session.isModuleInstalled("cm"))
            mods.add(new ModuleDef("cm", "Cash Management",
                List.of(cmCashbookTransactions, cmCashbookListing, cmCashbookByType,
                        cmCashbookDistributions, cmCashbookLedger, cmDocumentListing,
                        cmBankReconciliation, cmReceiptListing, cmFcMatch)));
        if (session.isModuleInstalled("po"))
            mods.add(new ModuleDef("po", "Purchasing",
                List.of(poInSequence, poSummary, poDetail, purchaseIndex, poVariance,
                        poUninvoicedGoods, poUninvoicedSundries, poSundriesRecon, poExpedite)));
        if (session.isModuleInstalled("sm"))
            mods.add(new ModuleDef("sm", "Inventory Management",
                List.of(smStockItemInquiry, smStockAvailability,
                        smMovementsDetail, smMovementsSummary, smValuation, smAvailability,
                        smReorder, smInactive, smItemStatus, smSerialBatch, smConsignGl,
                        smConsignStock, smSalesHistory, smTxnByCustomer, smPurchaseAnalysis,
                        smPriceList)));
        if (session.isModuleInstalled("fa"))
            mods.add(new ModuleDef("fa", "Fixed Assets",
                List.of(assetRegister, depreciation, acquiredRetired, txnList)));
        if (session.isModuleInstalled("py"))
            mods.add(new ModuleDef("py", "Payroll",
                List.of(payrollSummary, employeeList, ytdPayments,
                        histDetail, histSummary, dednSuper, deptExpenses,
                        payPeriodSummary, payrunGlDetail, timesheetHist,
                        dednStatus, superByFund, extendedSuper)));
        if (session.isModuleInstalled("bas"))
            mods.add(new ModuleDef("bas", "Business Activity Statement",
                List.of(basStatement, detailedBas, basTransactions, basByGl)));
        modules = mods;
    }

    /* ── Module selection ───────────────────────────────────────── */
    private void selectModule(ModuleDef mod) {
        activeModule = mod;
        searchField.clear();

        if (shellRail != null) {
            boolean isFav = (mod == null);
            shellRail.setFavouritesActive(isFav);
            shellRail.setActive(isFav ? null : Module.byRouteId(mod.getId()));
        }

        if (mod == null) {
            moduleTitle.setText("Favourites");
            renderFavourites();
        } else {
            moduleTitle.setText(mod.getLabel());
            buildReportRows(mod.getReports(), mod.getId());
        }
    }

    /* ── Report rows ────────────────────────────────────────────── */
    private void buildReportRows(List<ReportDef> reports, String moduleId) {
        reportList.getChildren().clear();
        if (emptyLabel != null) emptyLabel.setVisible(false);
        reports.forEach(r -> reportList.getChildren().add(buildReportCard(r, moduleId)));
        reportCount.setText(reports.size() + " reports");
    }

    private void renderFavourites() {
        reportList.getChildren().clear();
        boolean any = false;
        for (ModuleDef mod : modules) {
            for (ReportDef r : mod.getReports()) {
                if (favStore.isFavourite(mod.getId() + ":" + r.getName())) {
                    reportList.getChildren().add(buildReportCard(r, mod.getId()));
                    any = true;
                }
            }
        }
        reportCount.setText(favStore.count() + " reports");
        if (emptyLabel != null) emptyLabel.setVisible(!any);
    }

    /**
     * Drops a trailing COBOL program-code parenthetical from a report hint —
     * e.g. " (SMTL01)", " (GLTL14/15)", " (SMTL15 + SMTL24)" — leaving plain text.
     * Only strips an all-caps/digit parenthetical so normal-word hints survive.
     */
    private static String stripProgramCode(String desc) {
        if (desc == null) return "";
        return desc.replaceAll("\\s*\\([A-Z0-9 +/&-]+\\)\\s*$", "").trim();
    }

    /* ── Build one card — icon + name/hint + star, NO format buttons ── */
    private HBox buildReportCard(ReportDef report, String moduleId) {
        String favKey = moduleId + ":" + report.getName();

        HBox card = new HBox(12);
        card.getStyleClass().add("report-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setUserData(report.getLabel().toLowerCase()); // for search

        /* Coloured icon badge */
        StackPane iconBadge = new StackPane();
        iconBadge.getStyleClass().addAll("report-icon-badge",
            MODULE_STYLE.getOrDefault(moduleId, "icon-fa"));
        FontIcon icon = new FontIcon(report.getIconLiteral());
        icon.getStyleClass().add("report-badge-icon");
        iconBadge.getChildren().add(icon);

        /* Name + hint — fills all available width */
        VBox body = new VBox(3);
        HBox.setHgrow(body, Priority.ALWAYS);
        Label name = new Label(report.getLabel());
        name.getStyleClass().add("report-name");
        Label hint = new Label(stripProgramCode(report.getDescription()));
        hint.getStyleClass().add("report-hint");
        body.getChildren().addAll(name, hint);

        /* Favourite star — right-aligned, no format buttons */
        Label star = new Label(favStore.isFavourite(favKey) ? "★" : "☆");
        star.getStyleClass().addAll("fav-star",
            favStore.isFavourite(favKey) ? "fav-star-active" : "");
        star.setOnMouseClicked(e -> {
            e.consume(); // don't trigger card click
            favStore.toggle(favKey);
            boolean nowFav = favStore.isFavourite(favKey);
            star.setText(nowFav ? "★" : "☆");
            if (nowFav) star.getStyleClass().add("fav-star-active");
            else        star.getStyleClass().remove("fav-star-active");
            refreshFavBadge();
            if (activeModule == null) renderFavourites();
        });

        /* Card click → open selection screen */
        card.setOnMouseClicked(e -> openSelectionScreen(report, moduleId));
        card.getStyleClass().add("report-card-clickable");

        card.getChildren().addAll(iconBadge, body, star);
        return card;
    }

    /* ── Open the report's selection/params screen ────────────────
     *
     * Tries to load /fxml/reports/{moduleId}/{report.getName()}.fxml.
     * If the FXML doesn't exist yet (i.e. report not built in any wave),
     * falls back to the "Coming soon" alert so future-wave cards keep
     * their current behaviour automatically.
     */
    private void openSelectionScreen(ReportDef report, String moduleId) {
        String fxmlPath = "/fxml/reports/" + moduleId + "/" + report.getName() + ".fxml";
        java.net.URL fxmlUrl = getClass().getResource(fxmlPath);
        if (fxmlUrl == null) {
            comingSoon(report.getLabel());
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            Stage dialog = new Stage();
            dialog.initOwner(reportList.getScene().getWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                getClass().getResource("/css/fixedassets.css").toExternalForm());
            scene.getStylesheets().add(
                getClass().getResource("/css/reporting.css").toExternalForm());
            scene.getStylesheets().add(
                getClass().getResource(AppMode.themeCssPath()).toExternalForm());
            dialog.setScene(scene);
            dialog.setTitle(report.getLabel());
            dialog.setResizable(false);
            dialog.show();
        } catch (Exception ex) {
            showError("Could not open report", ex.getMessage());
        }
    }

    /* ── Favourites badge refresh ───────────────────────────────── */
    private void refreshFavBadge() {
        if (shellRail != null) shellRail.refreshFavouritesCount(favStore.count());
    }

    /* ── Search ─────────────────────────────────────────────────── */
    private void filterReports(String query) {
        if (activeModule == null) return;
        String q = query == null ? "" : query.trim().toLowerCase();
        reportList.getChildren().forEach(node -> {
            String key = (String) node.getUserData();
            boolean show = q.isEmpty() || (key != null && key.contains(q));
            node.setVisible(show);
            node.setManaged(show);
        });
    }

    /* ── Navigation ─────────────────────────────────────────────── */
    private void openCompanyYearSwitcher() {
        Window owner = headerSlot.getScene() != null ? headerSlot.getScene().getWindow() : null;
        mainMenu.showCompanyYearDialog(owner);
        // AppSession is now updated — rebuild the module registry and refresh
        // the shared shell so modules not installed for the previous company
        // appear/disappear and the header chip/user name reflect the switch.
        refreshHeader();
        buildModuleRegistry();
        if (shellRail != null) {
            shellRail.refreshVisibility(m -> !"sys".equals(m.getRouteId()) && session.isModuleInstalled(m.getRouteId()));
        }
        if (!modules.isEmpty()) selectModule(modules.get(0));
    }

    private void onSignOut() { javafx.application.Platform.exit(); }

    /* ── Jasper bridge — every selection screen controller calls this ──
     *
     * runJasperReport pulls the standard params from AppSession, merges in
     * any per-report extras, runs the export off the FX thread, then prompts
     * the user to save + opens the file with the system default viewer.
     */
    public void runJasperReport(String reportPath, Map<String, Object> extraParams,
                                  String format, javafx.stage.Window owner) {
        Map<String, Object> params = buildStandardParams();
        params.putAll(extraParams);

        new Thread(() -> {
            try {
                byte[] data;
                String ext;
                if ("pdf".equals(format)) {
                    data = jasper.exportPdf(reportPath, params);
                    ext = ".pdf";
                } else {
                    data = jasper.exportExcel(reportPath, params);
                    ext = ".xlsx";
                }
                final byte[] finalData = data;
                final String finalExt = ext;
                javafx.application.Platform.runLater(() ->
                    saveOrOpen(finalData, reportPath, finalExt, owner));
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                    showError("Report failed", ex.getMessage()));
            }
        }, "jasper-" + reportPath).start();
    }

    /**
     * Variant of runJasperReport for reports whose query is too dynamic
     * for a static .jrxml SQL block — the caller pre-fetches rows and
     * passes a JRDataSource (typically a JRBeanCollectionDataSource).
     * Used by AR / AP ageing.
     */
    public void runJasperReportWithDataSource(String reportPath,
                                                Map<String, Object> extraParams,
                                                net.sf.jasperreports.engine.JRDataSource dataSource,
                                                String format,
                                                javafx.stage.Window owner) {
        Map<String, Object> params = buildStandardParams();
        params.putAll(extraParams);

        new Thread(() -> {
            try {
                byte[] data;
                String ext;
                if ("pdf".equals(format)) {
                    data = jasper.exportPdfFromDataSource(reportPath, params, dataSource);
                    ext = ".pdf";
                } else {
                    data = jasper.exportExcelFromDataSource(reportPath, params, dataSource);
                    ext = ".xlsx";
                }
                final byte[] finalData = data;
                final String finalExt = ext;
                javafx.application.Platform.runLater(() ->
                    saveOrOpen(finalData, reportPath, finalExt, owner));
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                    showError("Report failed", ex.getMessage()));
            }
        }, "jasper-" + reportPath).start();
    }

    private Map<String, Object> buildStandardParams() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("COMPANY_NO",    session.getCompanyNo());
        p.put("YEAR_NO",       session.getYearNo());
        p.put("COMPANY_NAME",  session.getCompanyName());
        p.put("YEAR_DESC",     session.getYearDesc());
        p.put("YR_START_DATE", session.getYrStartDate() != null
            ? java.sql.Date.valueOf(session.getYrStartDate()) : null);
        p.put("YR_END_DATE",   session.getYrEndDate() != null
            ? java.sql.Date.valueOf(session.getYrEndDate()) : null);
        p.put("USER_ID",       session.getUserId());
        return p;
    }

    /**
     * Save the report to CPCNTRL.local_pc_dir using a derived filename, then
     * open it with the system default viewer. Falls back to a FileChooser if
     * the configured directory is missing, blank, or unwritable.
     *
     * <p>The {@code reportPath} ("fa/asset-register") is converted to a
     * filesystem-friendly slug ("fa_asset-register") and stamped with the
     * current timestamp so repeat runs don't overwrite earlier files.
     */
    /**
     * Public entry for reports that build their own bytes (e.g. an Apache POI
     * workbook for dynamic-column Excel) instead of going through Jasper.
     * {@code slug} becomes the filename stem; {@code ext} like ".xlsx".
     */
    public void saveAndOpen(byte[] data, String slug, String ext, javafx.stage.Window owner) {
        saveOrOpen(data, slug, ext, owner);
    }

    private void saveOrOpen(byte[] data, String reportPath, String ext, javafx.stage.Window owner) {
        String slug = reportPath.replace('/', '_').replace('\\', '_');
        // Millisecond precision so a bulk run firing several reports in the
        // same second doesn't have them overwrite each other on disk.
        String stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String filename = slug + "_" + stamp + ext;

        java.io.File file = resolveOutputFile(filename, ext, owner);
        if (file == null) return;  // user cancelled the fallback chooser

        // Step 1 — write to disk.
        try {
            java.nio.file.Files.createDirectories(file.toPath().getParent());
            java.nio.file.Files.write(file.toPath(), data);
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Could not save file", ex.toString());
            return;
        }

        // Step 2 — open with system viewer.
        // Use OS-native commands instead of java.awt.Desktop — JavaFX
        // apps on Windows often report Desktop as unsupported because
        // AWT was never initialised, so Desktop.open silently no-ops.
        if (!openWithOsViewer(file)) {
            infoSaved(file);
        }
    }

    /**
     * Open the file with the OS's default viewer. Returns true on success.
     *
     * <p>Windows: {@code cmd /c start "" "<path>"} — the empty title arg is
     * what {@code start} expects when the path itself is quoted.
     * <br>Mac: {@code open <path>}.
     * <br>Linux/other: {@code xdg-open <path>}.
     */
    private boolean openWithOsViewer(java.io.File file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        java.util.List<String> cmd;
        if (os.contains("win")) {
            cmd = java.util.List.of("cmd", "/c", "start", "", file.getAbsolutePath());
        } else if (os.contains("mac") || os.contains("darwin")) {
            cmd = java.util.List.of("open", file.getAbsolutePath());
        } else {
            cmd = java.util.List.of("xdg-open", file.getAbsolutePath());
        }
        try {
            new ProcessBuilder(cmd).inheritIO().start();
            return true;
        } catch (Exception ex) {
            System.err.println("Could not open " + file + " via " + cmd.get(0) + ": " + ex);
            return false;
        }
    }

    /**
     * Return the target File. Prefer CPCNTRL.local_pc_dir; if that's missing
     * or unwritable, fall back to a FileChooser so the user still gets the file.
     */
    private java.io.File resolveOutputFile(String filename, String ext, javafx.stage.Window owner) {
        String dir = cpCntrl.getLocalPcDir(session.getCompanyNo());
        if (dir != null && !dir.isBlank()) {
            java.io.File f = new java.io.File(dir, filename);
            try {
                java.nio.file.Files.createDirectories(f.toPath().getParent());
                return f;
            } catch (Exception ex) {
                System.err.println("local_pc_dir not writable (" + dir
                    + ") — falling back to chooser: " + ex);
            }
        }
        // Fallback — let the user pick.
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Save Report");
        fc.setInitialFileName(filename);
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
            ext.equals(".pdf") ? "PDF" : "Excel", "*" + ext));
        java.io.File chosen = fc.showSaveDialog(owner);
        if (chosen == null) return null;
        return chosen.getName().toLowerCase().endsWith(ext)
            ? chosen
            : new java.io.File(chosen.getParentFile(), chosen.getName() + ext);
    }

    private void infoSaved(java.io.File file) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Saved");
        a.setHeaderText("Report saved");
        a.setContentText("Saved to:\n" + file.getAbsolutePath()
            + "\n\n(Couldn't auto-open — open it manually from there.)");
        a.showAndWait();
    }

    private void showError(String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(message);
        a.showAndWait();
    }

    /* ── Helpers ─────────────────────────────────────────────────── */
    private void comingSoon(String name) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Coming soon");
        a.setHeaderText(name);
        a.setContentText("Selection screen for this report is being developed.");
        a.showAndWait();
    }

}
