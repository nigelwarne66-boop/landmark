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
package com.landmarksoftware.ui;

import com.landmarksoftware.report.AssetRegisterViewerService;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.stage.*;
import com.landmarksoftware.model.AppSession;
import org.springframework.jdbc.core.JdbcTemplate;
import com.landmarksoftware.ui.LookupDialog;
import com.landmarksoftware.ui.LookupDialog.LookupType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FAAS10 — Asset Register Parameter Screen.
 * Matches FAAS10S0 selection screen layout.
 */
@Component
public class AssetRegisterParamsController {

    private final AssetRegisterViewerService assetRegisterViewerService;
    private final JdbcTemplate      jdbc;
    private final AppSession         appSession;

    public AssetRegisterParamsController(AssetRegisterViewerService assetRegisterViewerService,
                                          JdbcTemplate jdbc,
                                          AppSession appSession) {
        this.assetRegisterViewerService = assetRegisterViewerService;
        this.jdbc              = jdbc;
        this.appSession        = appSession;
    }

    public void show(int companyNo, Window owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Fixed Asset Register — FAAS10");
        dlg.setResizable(false);

        VBox root = new VBox(0);
        root.setStyle("-fx-background-color:#F2F1EC;");

        // ── Header ────────────────────────────────────────────────
        Label hdr = new Label("Fixed Asset Register — Selection Criteria");
        hdr.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:white;" +
                     "-fx-background-color:#1A6EF5;-fx-padding:10 16 10 16;");
        hdr.setMaxWidth(Double.MAX_VALUE);

        // ── Form ──────────────────────────────────────────────────
        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(9);
        form.setPadding(new Insets(16, 16, 8, 16));

        // ── Asset range ──────────────────────────────────────────
        TextField fStart = entryField(20);
        TextField fEnd   = entryField(20);
        fEnd.setText("zzzzzzzzzzzzzzzzzzzz");  // default = all

        // Lookup buttons for asset no
        Button btnStartLookup = lookupBtn();
        Button btnEndLookup   = lookupBtn();
        btnStartLookup.setOnAction(e -> {
            String picked = pickAsset(companyNo, dlg);
            if (picked != null) fStart.setText(picked);
        });
        btnEndLookup.setOnAction(e -> {
            String picked = pickAsset(companyNo, dlg);
            if (picked != null) fEnd.setText(picked);
        });

        form.add(lbl("Start Asset No:"), 0, 0);
        form.add(hbox(fStart, btnStartLookup), 1, 0);
        form.add(lbl("End Asset No:"),   2, 0);
        form.add(hbox(fEnd, btnEndLookup), 3, 0);

        // ── Location ─────────────────────────────────────────────
        TextField fLocn     = entryField(6);
        Label     lLocnDesc = descLabel();
        Button    btnLocn   = lookupBtn();
        fLocn.focusedProperty().addListener((o, old, focused) -> {
            if (!focused) lLocnDesc.setText(lookupDesc("FACODLO", "loc_code", fLocn.getText().trim()));
        });
        btnLocn.setOnAction(e -> {
            String picked = pickFromTable(dlg, "FACODLO", "loc_code", "Location");
            if (picked != null) { fLocn.setText(picked);
                lLocnDesc.setText(lookupDesc("FACODLO", "loc_code", picked)); }
        });
        form.add(lbl("Location:"), 0, 1);
        form.add(hbox(fLocn, btnLocn), 1, 1);
        form.add(lLocnDesc, 2, 1, 2, 1);

        // ── Department ───────────────────────────────────────────
        TextField fDept     = entryField(6);
        Label     lDeptDesc = descLabel();
        Button    btnDept   = lookupBtn();
        fDept.focusedProperty().addListener((o, old, focused) -> {
            if (!focused) lDeptDesc.setText(lookupDesc("FACODDT", "dept_code", fDept.getText().trim()));
        });
        btnDept.setOnAction(e -> {
            String picked = pickFromTable(dlg, "FACODDT", "dept_code", "Department");
            if (picked != null) { fDept.setText(picked);
                lDeptDesc.setText(lookupDesc("FACODDT", "dept_code", picked)); }
        });
        form.add(lbl("Department:"), 0, 2);
        form.add(hbox(fDept, btnDept), 1, 2);
        form.add(lDeptDesc, 2, 2, 2, 1);

        // ── Group ────────────────────────────────────────────────
        TextField fGroup     = entryField(6);
        Label     lGroupDesc = descLabel();
        Button    btnGroup   = lookupBtn();
        fGroup.focusedProperty().addListener((o, old, focused) -> {
            if (!focused) lGroupDesc.setText(lookupDesc("FACODGR", "grp_code", fGroup.getText().trim()));
        });
        btnGroup.setOnAction(e -> {
            String picked = pickFromTable(dlg, "FACODGR", "grp_code", "Group");
            if (picked != null) { fGroup.setText(picked);
                lGroupDesc.setText(lookupDesc("FACODGR", "grp_code", picked)); }
        });
        form.add(lbl("Group:"), 0, 3);
        form.add(hbox(fGroup, btnGroup), 1, 3);
        form.add(lGroupDesc, 2, 3, 2, 1);

        // ── Sub-Group ────────────────────────────────────────────
        TextField fSubGrp     = entryField(6);
        Label     lSubGrpDesc = descLabel();
        Button    btnSubGrp   = lookupBtn();
        fSubGrp.focusedProperty().addListener((o, old, focused) -> {
            if (!focused) lSubGrpDesc.setText(lookupDesc("FACODSG", "subgrp_code", fSubGrp.getText().trim()));
        });
        btnSubGrp.setOnAction(e -> {
            String picked = pickFromTable(dlg, "FACODSG", "subgrp_code", "Sub-Group");
            if (picked != null) { fSubGrp.setText(picked);
                lSubGrpDesc.setText(lookupDesc("FACODSG", "subgrp_code", picked)); }
        });
        form.add(lbl("Sub-Group:"), 0, 4);
        form.add(hbox(fSubGrp, btnSubGrp), 1, 4);
        form.add(lSubGrpDesc, 2, 4, 2, 1);

        // ── Separator ─────────────────────────────────────────────
        Separator sep1 = new Separator();
        form.add(sep1, 0, 5, 4, 1);

        // ── Asset status ──────────────────────────────────────────
        CheckBox cbU = chk("Unposted");
        CheckBox cbA = chk("Active");   cbA.setSelected(true);
        CheckBox cbH = chk("On Hold");  cbH.setSelected(true);
        CheckBox cbN = chk("Inactive");
        CheckBox cbR = chk("Retired");
        HBox statusBox = new HBox(12, cbU, cbA, cbH, cbN, cbR);
        form.add(lbl("Asset Status:"), 0, 6);
        form.add(statusBox, 1, 6, 3, 1);

        // ── Leased indicator ──────────────────────────────────────
        ToggleGroup lgLease = new ToggleGroup();
        RadioButton rbAll   = radio("All",         lgLease, true);
        RadioButton rbLease = radio("Leased only", lgLease, false);
        RadioButton rbNon   = radio("Non-leased",  lgLease, false);
        HBox leaseBox = new HBox(12, rbAll, rbLease, rbNon);
        form.add(lbl("Leased:"), 0, 7);
        form.add(leaseBox, 1, 7, 3, 1);

        // ── Depreciation type ─────────────────────────────────────
        ToggleGroup lgBT   = new ToggleGroup();
        RadioButton rbBook = radio("Book", lgBT, true);
        RadioButton rbTax  = radio("Tax",  lgBT, false);
        HBox btBox = new HBox(12, rbBook, rbTax);
        form.add(lbl("Depreciation:"), 0, 8);
        form.add(btBox, 1, 8, 3, 1);

        // ── Sort sequence ─────────────────────────────────────────
        ToggleGroup lgSeq   = new ToggleGroup();
        RadioButton rbLoc   = radio("Location / Group", lgSeq, true);
        RadioButton rbDept  = radio("Department / Group", lgSeq, false);
        RadioButton rbGrp   = radio("Group only",        lgSeq, false);
        RadioButton rbAsset = radio("Asset No",          lgSeq, false);
        HBox seqBox = new HBox(12, rbLoc, rbDept, rbGrp, rbAsset);
        form.add(lbl("Sort by:"), 0, 9);
        form.add(seqBox, 1, 9, 3, 1);

        // ── Buttons ───────────────────────────────────────────────
        Button btnRun = new Button("▶  Run Report");
        btnRun.setDefaultButton(true);
        btnRun.setStyle(
            "-fx-background-color:#1A6EF5;-fx-text-fill:white;" +
            "-fx-font-weight:bold;-fx-background-radius:7;" +
            "-fx-padding:8 20 8 20;-fx-cursor:hand;-fx-font-size:12px;");
        btnRun.setOnMouseEntered(e ->
            btnRun.setStyle(btnRun.getStyle().replace("#1A6EF5","#155EC7")));
        btnRun.setOnMouseExited(e ->
            btnRun.setStyle(btnRun.getStyle().replace("#155EC7","#1A6EF5")));

        Button btnCancel = new Button("Cancel");
        btnCancel.setStyle(
            "-fx-background-color:white;-fx-border-color:#D0CFC8;" +
            "-fx-border-radius:7;-fx-background-radius:7;" +
            "-fx-padding:7 16 7 16;-fx-cursor:hand;-fx-font-size:12px;");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> dlg.close());

        HBox btnBar = new HBox(10, btnRun, btnCancel);
        btnBar.setPadding(new Insets(12, 16, 14, 16));
        btnBar.setAlignment(Pos.CENTER_RIGHT);
        btnBar.setStyle(
            "-fx-background-color:#F2F1EC;" +
            "-fx-border-color:rgba(0,0,0,0.10) transparent transparent transparent;" +
            "-fx-border-width:0.5 0 0 0;");

        btnRun.setOnAction(e -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("companyNo",  String.valueOf(companyNo));
            params.put("bookTaxInd", rbBook.isSelected() ? "B" : "T");

            String startVal = fStart.getText().trim();
            String endVal   = fEnd.getText().trim();
            if (!startVal.isEmpty()) params.put("startAssetNo", startVal);
            if (!endVal.isEmpty() && !endVal.startsWith("zzz"))
                params.put("endAssetNo", endVal);

            if (!fLocn.getText().trim().isEmpty())   params.put("printLocn",     fLocn.getText().trim());
            if (!fDept.getText().trim().isEmpty())   params.put("printDept",     fDept.getText().trim());
            if (!fGroup.getText().trim().isEmpty())  params.put("printGroup",    fGroup.getText().trim());
            if (!fSubGrp.getText().trim().isEmpty()) params.put("printSubGroup", fSubGrp.getText().trim());

            if (cbU.isSelected()) params.put("statusU", "Y");
            if (cbA.isSelected()) params.put("statusA", "Y");
            if (cbH.isSelected()) params.put("statusH", "Y");
            if (cbN.isSelected()) params.put("statusN", "Y");
            if (cbR.isSelected()) params.put("statusR", "Y");

            if (rbLease.isSelected()) params.put("leasedInd", "L");
            else if (rbNon.isSelected()) params.put("leasedInd", "N");

            if (rbDept.isSelected())       params.put("sortOrder", "dept");
            else if (rbGrp.isSelected())   params.put("sortOrder", "grp");
            else if (rbAsset.isSelected()) params.put("sortOrder", "asset");

            openBrowser(assetRegisterViewerService.buildViewerUrl(params));
            dlg.close();
        });

        root.getChildren().addAll(hdr, form, btnBar);
        dlg.setScene(new Scene(root, 620, 450));
        dlg.show();
    }

    // ── Lookup dialogs ────────────────────────────────────────────

    /** Pick an asset no from a simple list dialog */
    private String pickAsset(int companyNo, Window owner) {
        return pickFromQueryDialog(owner, "Select Asset",
            "SELECT asset_no, desc_1 FROM FAASSET WHERE company_no = " + companyNo +
            " ORDER BY asset_no",
            "Asset No", "Description");
    }

    /** Pick a code from a code table */
    private String pickFromTable(Window owner, String table,
                                  String codeCol, String title) {
        return pickFromQueryDialog(owner, "Select " + title,
            "SELECT " + codeCol + ", desc1 FROM " + table + " ORDER BY " + codeCol,
            title, "Description");
    }

    /** Generic pick-from-list dialog — returns the code column value */
    private String pickFromQueryDialog(Window owner, String title,
                                        String sql, String col1, String col2) {
        Stage picker = new Stage();
        picker.initOwner(owner);
        picker.initModality(Modality.WINDOW_MODAL);
        picker.setTitle(title);

        TableView<String[]> tbl = new TableView<>();
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tbl.setPrefHeight(360);

        TableColumn<String[], String> c1 = new TableColumn<>(col1);
        c1.setCellValueFactory(d ->
            new javafx.beans.property.SimpleStringProperty(d.getValue()[0]));
        TableColumn<String[], String> c2 = new TableColumn<>(col2);
        c2.setCellValueFactory(d ->
            new javafx.beans.property.SimpleStringProperty(d.getValue()[1]));
        tbl.getColumns().addAll(c1, c2);

        try {
            jdbc.query(sql, rs -> {
                tbl.getItems().add(new String[]{
                    rs.getString(1),
                    rs.getString(2) == null ? "" : rs.getString(2)
                });
            });
        } catch (Exception ex) { ex.printStackTrace(); }

        // Search box
        TextField search = new TextField();
        search.setPromptText("Search…");
        search.setStyle("-fx-font-size:12px;");
        search.textProperty().addListener((o, old, v) -> {
            String lv = v.toLowerCase();
            tbl.getItems().filtered(r ->
                r[0].toLowerCase().contains(lv) || r[1].toLowerCase().contains(lv));
        });

        // Filter properly
        javafx.collections.ObservableList<String[]> allItems =
            javafx.collections.FXCollections.observableArrayList(tbl.getItems());
        javafx.collections.transformation.FilteredList<String[]> filtered =
            new javafx.collections.transformation.FilteredList<>(allItems, r -> true);
        tbl.setItems(filtered);
        search.textProperty().addListener((o, old, v) -> {
            String lv = v.toLowerCase();
            filtered.setPredicate(r ->
                v.isEmpty() ||
                r[0].toLowerCase().contains(lv) ||
                r[1].toLowerCase().contains(lv));
        });

        final String[] result = {null};

        Button btnOk = new Button("Select");
        btnOk.setDefaultButton(true);
        btnOk.setStyle("-fx-background-color:#1A6EF5;-fx-text-fill:white;" +
                       "-fx-background-radius:7;-fx-padding:6 14 6 14;-fx-cursor:hand;");
        btnOk.setOnAction(e -> {
            String[] sel = tbl.getSelectionModel().getSelectedItem();
            if (sel != null) { result[0] = sel[0]; picker.close(); }
        });
        tbl.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String[] sel = tbl.getSelectionModel().getSelectedItem();
                if (sel != null) { result[0] = sel[0]; picker.close(); }
            }
        });

        Button btnCancel = new Button("Cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setStyle("-fx-background-color:white;-fx-border-color:#D0CFC8;" +
                           "-fx-border-radius:7;-fx-padding:5 12 5 12;-fx-cursor:hand;");
        btnCancel.setOnAction(e -> picker.close());

        HBox bar = new HBox(8, btnOk, btnCancel);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 10, 10, 10));

        VBox layout = new VBox(8,
            new VBox(4, new Label("Search:"), search),
            tbl, bar);
        layout.setPadding(new Insets(10));
        layout.setStyle("-fx-background-color:#F2F1EC;");

        picker.setScene(new Scene(layout, 400, 460));
        picker.showAndWait();
        return result[0];
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String lookupDesc(String table, String keyCol, String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return jdbc.queryForObject(
                "SELECT desc1 FROM " + table + " WHERE " + keyCol + " = ?",
                String.class, value);
        } catch (Exception e) { return "(not found)"; }
    }

    private void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win"))
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            else if (os.contains("mac"))
                new ProcessBuilder("open", url).start();
            else
                new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    private static HBox hbox(javafx.scene.Node... nodes) {
        HBox b = new HBox(4, nodes); b.setAlignment(Pos.CENTER_LEFT); return b;
    }
    private static TextField entryField(int chars) {
        TextField f = new TextField();
        f.setPrefWidth(chars * 8.0);
        f.setStyle("-fx-font-size:12px;-fx-font-family:'Courier New',monospace;");
        return f;
    }
    private static Button lookupBtn() {
        Button b = new Button("…");
        b.setStyle("-fx-background-color:white;-fx-border-color:#D0CFC8;" +
                   "-fx-border-radius:4;-fx-padding:3 7 3 7;-fx-cursor:hand;-fx-font-size:11px;");
        b.setTooltip(new Tooltip("Browse / lookup"));
        return b;
    }
    private static Label lbl(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:12px;-fx-text-fill:#374151;");
        l.setMinWidth(105);
        return l;
    }
    private static Label descLabel() {
        Label l = new Label();
        l.setStyle("-fx-font-size:11px;-fx-text-fill:#1A6EF5;-fx-font-style:italic;");
        l.setMinWidth(150);
        return l;
    }
    private static CheckBox chk(String t) {
        CheckBox c = new CheckBox(t); c.setStyle("-fx-font-size:12px;"); return c;
    }
    private static RadioButton radio(String t, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(t);
        r.setToggleGroup(g); r.setSelected(sel);
        r.setStyle("-fx-font-size:12px;"); return r;
    }
}
