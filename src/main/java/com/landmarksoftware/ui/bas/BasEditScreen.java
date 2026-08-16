/*
 * Copyright (c) 2026 Landmark Software Pty Ltd.
 * All rights reserved.
 *
 * This software is proprietary and confidential.
 * Unauthorised copying, modification, distribution or use
 * of this software, via any medium, is strictly prohibited.
 * Decompilation and reverse engineering are expressly forbidden.
 *
 * Licenced under the terms of the Landmark Software Licence Agreement.
 */
package com.landmarksoftware.ui.bas;

import com.landmarksoftware.db.tables.records.CpbashdRecord;
import com.landmarksoftware.service.bas.BasProcessingService;
import com.landmarksoftware.service.bas.BasReasonCodeService;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jooq.DSLContext;

import static com.landmarksoftware.db.tables.Cpbashd.CPBASHD;

/**
 * CPBA10 — the consolidated BAS Edit/Inquire screen: one window, two tabs
 * ("GST" and "Other Taxes"), replacing COBOL's S4/S4A/S4B/S6/S6A/S6B/S6C
 * chain. Every editable ATO label is a click-to-drill amount that opens the
 * shared P4 transaction list (see {@link BasTransactionListDialog})
 * filtered to that label's {@code bas_code}, except 5A/6A (dedicated
 * dialogs — {@link BasIncomeTaxDialog}/{@link BasFbtDialog}) and 7A/7C/7D
 * (single-figure dialog — {@link BasManualFigureDialog}).
 *
 * <p>{@code readOnly=true} (Inquire) disables every drill so nothing can be
 * changed, without duplicating the screen.
 *
 * <p><b>4 / 5A / 6A all open the same screen</b> — verified against real-app
 * screenshots 2026-08-13: the COBOL "BAS CALCULATION SHEET - WITHHOLDING
 * TAXES" window shows Withholding (W1-W4), Income Tax Instalment (T1-T4) and
 * FBT Instalment (F1-F4) together, reachable by clicking any of 4/5A/6A. See
 * {@link BasWithholdingInstalmentsDialog}.
 */
final class BasEditScreen {

    private BasEditScreen() { }

    static void show(Window owner, BasProcessingService service, BasReasonCodeService reasonCodeService,
                      DSLContext dsl, String basGroup, int basNo, String userId, boolean readOnly, Runnable onChanged) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle((readOnly ? "Inquire" : "Edit") + " BAS — " + basGroup + "/" + basNo);

        Label lblHeader = new Label();
        lblHeader.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#1A1A2E;");
        Label lblSub = new Label();
        lblSub.setStyle("-fx-font-size:11px;-fx-text-fill:#888780;");
        VBox headerBox = new VBox(2, lblHeader, lblSub);
        headerBox.setPadding(new Insets(14, 20, 14, 20));
        headerBox.setStyle("-fx-background-color:#FFFFFF;" +
            "-fx-border-color:transparent transparent rgba(0,0,0,.10) transparent;" +
            "-fx-border-width:0 0 0.5 0;");

        TabPane tabs = new TabPane();
        Tab gstTab = new Tab("GST");
        Tab otherTab = new Tab("Other Taxes");
        gstTab.setClosable(false);
        otherTab.setClosable(false);
        tabs.getTabs().addAll(gstTab, otherTab);

        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            CpbashdRecord h = service.getHeader(basGroup, basNo);
            if (h == null) {
                BasUiSupport.showError("BAS not found", "This BAS run could no longer be found.");
                dlg.close();
                return;
            }
            boolean editable = !readOnly && BasUiSupport.trim(h.get(CPBASHD.BAS_STATUS)).isEmpty();
            lblHeader.setText(BasUiSupport.trim(h.get(CPBASHD.COMPANY_NAME)) + " — " + basGroup + "/" + basNo);
            lblSub.setText("Period " + h.get(CPBASHD.A3_FROM_DATE) + " to " + h.get(CPBASHD.A4_TO_DATE)
                + "  ·  Status: " + statusText(BasUiSupport.trim(h.get(CPBASHD.BAS_STATUS))));

            java.util.function.Consumer<String> drill = code -> {
                BasTransactionListDialog.show(dlg, service, dsl, basGroup, basNo, code, userId, !editable, null);
                refreshRef[0].run();
                if (onChanged != null) onChanged.run();
            };

            gstTab.setContent(buildGstTab(h, editable, drill));
            otherTab.setContent(buildOtherTaxesTab(dlg, service, reasonCodeService, basGroup, basNo, h,
                userId, editable, refreshRef[0], onChanged, drill));
        };
        refreshRef[0].run();

        BorderPane root = new BorderPane();
        root.setTop(headerBox);
        root.setCenter(tabs);

        dlg.setScene(new Scene(root, 900, 700));
        dlg.showAndWait();
    }

    // ── GST tab ──────────────────────────────────────────────────────────

    private static ScrollPane buildGstTab(CpbashdRecord h, boolean editable, java.util.function.Consumer<String> drill) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(20));

        box.getChildren().add(BasUiSupport.sectionHeader("GST Summary"));
        box.getChildren().add(BasUiSupport.amountRow("1A  GST on sales", h.get(CPBASHD.BAS_1A_GST_PAYABLE), false, null));
        box.getChildren().add(BasUiSupport.amountRow("1B  GST on purchases", h.get(CPBASHD.BAS_1B_GST_CREDITS), false, null));
        box.getChildren().add(BasUiSupport.amountRow("1C  Wine equalisation tax payable", h.get(CPBASHD.BAS_1C_WINE_EQUAL_PAYABLE), editable, () -> drill.accept("1C")));
        box.getChildren().add(BasUiSupport.amountRow("1D  Wine equalisation tax credits", h.get(CPBASHD.BAS_1D_WINE_EQUAL_CREDITS), editable, () -> drill.accept("1D")));
        box.getChildren().add(BasUiSupport.amountRow("1E  Luxury car tax payable", h.get(CPBASHD.BAS_1E_LUXURY_CAR_PAYABLE), editable, () -> drill.accept("1E")));
        box.getChildren().add(BasUiSupport.amountRow("1F  Luxury car tax credits", h.get(CPBASHD.BAS_1F_LUXURY_CAR_CREDITS), editable, () -> drill.accept("1F")));
        box.getChildren().add(BasUiSupport.amountRow("1G  Sales tax credits", h.get(CPBASHD.BAS_1G_SALES_TAX_CREDITS), editable, () -> drill.accept("1G")));
        box.getChildren().add(BasUiSupport.amountRow("2A  GST payable", h.get(CPBASHD.BAS_2A_GST_PAYABLE), false, null));
        box.getChildren().add(BasUiSupport.amountRow("2B  GST credits", h.get(CPBASHD.BAS_2B_GST_CREDITS), false, null));
        box.getChildren().add(BasUiSupport.amountRow("3   Net GST", h.get(CPBASHD.BAS_3_NET_GST_AMT), false, null));

        VBox salesDetail = new VBox(4);
        salesDetail.getChildren().add(BasUiSupport.amountRow("G1  Total sales", h.get(CPBASHD.G1_TOTAL_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G2  Export sales", h.get(CPBASHD.G2_EXPORT_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G3  GST-free sales", h.get(CPBASHD.G3_TAX_FREE_SUPPLIES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G4  Input-taxed sales", h.get(CPBASHD.G4_INPUT_TAXED_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G5  Total non-taxable sales", h.get(CPBASHD.G5_TOTAL_NON_TAX_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G6  Total taxable sales", h.get(CPBASHD.G6_TOTAL_TAXABLE_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G7  Adjustments", h.get(CPBASHD.G7_SALES_ADJUSTMENTS), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G8  Total sales subject to GST", h.get(CPBASHD.G8_TOTAL_GST_SALES), false, null));
        salesDetail.getChildren().add(BasUiSupport.amountRow("G9  GST on sales", h.get(CPBASHD.G9_GST_ON_SALES), false, null));
        TitledPane salesPane = new TitledPane("Calculation detail — Sales (G1-G9)", salesDetail);
        salesPane.setExpanded(false);

        VBox purchDetail = new VBox(4);
        purchDetail.getChildren().add(BasUiSupport.amountRow("G10 Capital purchases", h.get(CPBASHD.G10_CAPITAL_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G11 Non-capital purchases", h.get(CPBASHD.G11_NON_CAPITAL_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G12 Total purchases", h.get(CPBASHD.G12_TOTAL_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G13 Input-taxed purchases", h.get(CPBASHD.G13_INPUT_TAXED_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G14 GST-free purchases", h.get(CPBASHD.G14_TAX_FREE_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G15 Estimated private-use value", h.get(CPBASHD.G15_PRIVATE_USE_VALUE), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G16 Total non-taxable purchases", h.get(CPBASHD.G16_TOTAL_NONTAX_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G17 Total purchases subject to GST", h.get(CPBASHD.G17_TOTAL_TAXED_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G18 Adjustments", h.get(CPBASHD.G18_PURCH_ADJUSTMENTS), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G19 Total purchases subject to GST (adj.)", h.get(CPBASHD.G19_TOTAL_GST_PURCH), false, null));
        purchDetail.getChildren().add(BasUiSupport.amountRow("G20 GST on purchases", h.get(CPBASHD.G20_GST_ON_PURCH), false, null));
        TitledPane purchPane = new TitledPane("Calculation detail — Purchases (G10-G20)", purchDetail);
        purchPane.setExpanded(false);

        box.getChildren().addAll(salesPane, purchPane);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ── Other Taxes tab ──────────────────────────────────────────────────

    private static ScrollPane buildOtherTaxesTab(Window dlgOwner, BasProcessingService service,
            BasReasonCodeService reasonCodeService, String basGroup, int basNo, CpbashdRecord h,
            String userId, boolean editable, Runnable refresh, Runnable onChanged,
            java.util.function.Consumer<String> drill) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(20));

        Runnable openWithholding = () -> {
            BasWithholdingInstalmentsDialog.show(dlgOwner, service, reasonCodeService, basGroup, basNo, h, userId, refresh);
            refresh.run();
            if (onChanged != null) onChanged.run();
        };
        box.getChildren().add(BasUiSupport.sectionHeader("PAYG / FBT / Company Instalments"));
        box.getChildren().add(BasUiSupport.amountRow("4   PAYG tax withheld", h.get(CPBASHD.BAS_4_WITHHOLD_TAX), editable, openWithholding));
        box.getChildren().add(BasUiSupport.amountRow("5A  PAYG income tax instalment", h.get(CPBASHD.BAS_5A_INCOME_TAX_PAYABLE), editable, openWithholding));
        box.getChildren().add(BasUiSupport.amountRow("5B  PAYG instalment credit", h.get(CPBASHD.BAS_5B_INCOME_TAX_CREDITS), editable, () -> drill.accept("5B")));
        box.getChildren().add(BasUiSupport.amountRow("6A  FBT instalment", h.get(CPBASHD.BAS_6A_FBT_PAYABLE), editable, openWithholding));
        box.getChildren().add(BasUiSupport.amountRow("6B  FBT credit", h.get(CPBASHD.BAS_6B_FBT_CREDITS), editable, () -> drill.accept("6B")));
        box.getChildren().add(BasUiSupport.amountRow("7   Deferred company/fund instalment", h.get(CPBASHD.BAS_7_DEFERRED_TAX), editable, () -> drill.accept("7")));
        box.getChildren().add(BasUiSupport.amountRow("7A  Deferred GST — imports", h.get(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX), editable,
            () -> { BasManualFigureDialog.show(dlgOwner, service, basGroup, basNo, BasProcessingService.ManualFigureKind.SEVEN_A,
                "7A — Deferred Import Tax", h.get(CPBASHD.BAS_7A_DEFERRED_IMPORT_TAX), userId, refresh); refresh.run(); if (onChanged != null) onChanged.run(); }));
        box.getChildren().add(BasUiSupport.amountRow("7C  Fuel tax credit over-claim", h.get(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM), editable,
            () -> { BasManualFigureDialog.show(dlgOwner, service, basGroup, basNo, BasProcessingService.ManualFigureKind.SEVEN_C,
                "7C — Fuel Tax Credit Overclaim", h.get(CPBASHD.BAS_7C_FUEL_TAX_CRED_CLAIM), userId, refresh); refresh.run(); if (onChanged != null) onChanged.run(); }));
        box.getChildren().add(BasUiSupport.amountRow("7D  Fuel tax credit", h.get(CPBASHD.BAS_7D_FUEL_TAX_CREDIT), editable,
            () -> { BasManualFigureDialog.show(dlgOwner, service, basGroup, basNo, BasProcessingService.ManualFigureKind.SEVEN_D,
                "7D — Fuel Tax Credit", h.get(CPBASHD.BAS_7D_FUEL_TAX_CREDIT), userId, refresh); refresh.run(); if (onChanged != null) onChanged.run(); }));

        box.getChildren().add(BasUiSupport.sectionHeader("Summary"));
        box.getChildren().add(BasUiSupport.amountRow("8A  Total you owe the ATO", h.get(CPBASHD.BAS_8A_TAX_PAYABLE), false, null));
        box.getChildren().add(BasUiSupport.amountRow("8B  Total the ATO owes you", h.get(CPBASHD.BAS_8B_TAX_CREDITS), false, null));
        HBox row9 = BasUiSupport.amountRow("9   Net amount for this BAS", h.get(CPBASHD.BAS_9_NET_TAX_AMT), false, null);
        row9.setStyle("-fx-padding:8 0 0 0;");
        box.getChildren().add(row9);

        VBox withDetail = new VBox(4);
        withDetail.getChildren().add(BasUiSupport.amountRow("W1  Total wages", h.get(CPBASHD.W1_TOTAL_WAGES), false, null));
        withDetail.getChildren().add(BasUiSupport.amountRow("W2  Wages withheld", h.get(CPBASHD.W2_WAGES_WITHHELD), false, null));
        withDetail.getChildren().add(BasUiSupport.amountRow("W3  Investment withheld", h.get(CPBASHD.W3_INVESTMENT_WITHHELD), false, null));
        withDetail.getChildren().add(BasUiSupport.amountRow("W4  Other payments withheld", h.get(CPBASHD.W4_PAYMENTS_WITHHELD), false, null));
        TitledPane withPane = new TitledPane("Calculation detail — Withholding (W1-W4)", withDetail);
        withPane.setExpanded(false);
        box.getChildren().add(withPane);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static String statusText(String code) {
        return switch (code) {
            case "C" -> "Committed";
            case "D" -> "Cancelled";
            case "P" -> "Posting";
            default -> "Draft";
        };
    }
}
