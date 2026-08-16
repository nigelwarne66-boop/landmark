package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.ItemInquiryResult;
import com.landmarksoftware.service.sm.SmReportDataService.ItemLocationRow;
import com.landmarksoftware.service.sm.SmReportDataService.ItemTrxRow;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * SMTI01 — Stock Item Inquiry.
 *
 * <p>Pick a stock item, see it across all locations (qty + value on hand and
 * item status), and drill into the {@code smtrans} movement history for the
 * selected item+location. Mirrors the AP/AR transaction-inquiry pattern.
 */
@Component
@Scope("prototype")
public class SmStockItemInquiryController implements Initializable {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private AppSession session;
    @Autowired private SmReportDataService smReports;
    @Autowired private ReportsHubController hub;

    @FXML private ComboBox<SmReportDataService.CodeName> itemPicker;

    @FXML private HBox itemBar;
    @FXML private Label lblItemDesc;

    @FXML private TableView<ItemLocationRow> locTable;
    @FXML private TableColumn<ItemLocationRow, String>     colLoc;
    @FXML private TableColumn<ItemLocationRow, String>     colLocName;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colQtyOnHand;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colValueOnHand;
    @FXML private TableColumn<ItemLocationRow, String>     colStatus;

    @FXML private HBox totalsBar;
    @FXML private Label lblTotalQty;
    @FXML private Label lblTotalValue;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        List<SmReportDataService.CodeName> items = smReports.getItems(session);
        if (!items.isEmpty() && items.get(0).code().isBlank()) {
            items = new java.util.ArrayList<>(items);
            items.remove(0);
        }
        itemPicker.setItems(FXCollections.observableArrayList(items));
        PickerAutoComplete.install(itemPicker);

        itemBar.setVisible(false);   itemBar.setManaged(false);
        totalsBar.setVisible(false); totalsBar.setManaged(false);

        colLoc.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().locNo()));
        colLocName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().locName()));
        colQtyOnHand.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyOnHand()));
        colQtyOnHand.setCellFactory(c -> amtCell());
        colValueOnHand.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().valueOnHand()));
        colValueOnHand.setCellFactory(c -> amtCell());
        colStatus.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));

        locTable.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openTransactions(); });
    }

    @FXML
    void onFilter(ActionEvent e) {
        SmReportDataService.CodeName item = itemPicker.getSelectionModel().getSelectedItem();
        if (item == null || item.code().isBlank()) {
            alert("Select an item", "Please select a stock item before filtering.");
            return;
        }
        ItemInquiryResult result = smReports.getItemInquiry(session, item.code());
        locTable.setItems(FXCollections.observableArrayList(result.rows()));

        lblItemDesc.setText(result.stockCode() + " — " + result.desc1()
            + (result.desc2().isEmpty() ? "" : "  " + result.desc2()));
        itemBar.setVisible(true); itemBar.setManaged(true);

        lblTotalQty.setText("Total qty on hand: " + fmt(result.totalQtyOnHand())
            + (result.stockUnit().isEmpty() ? "" : " " + result.stockUnit()));
        lblTotalValue.setText("Total value on hand: " + fmt(result.totalValueOnHand()));
        totalsBar.setVisible(true); totalsBar.setManaged(true);
    }

    @FXML
    void onTransactions(ActionEvent e) { openTransactions(); }

    @FXML
    void onExportExcel(ActionEvent e) {
        if (locTable.getItems().isEmpty()) { alert("No data", "Filter an item first."); return; }
        try {
            byte[] data = buildExcel();
            Window owner = ((Node) e.getSource()).getScene().getWindow();
            hub.saveAndOpen(data, "sm-stock-item-inquiry", ".xlsx", owner);
        } catch (Exception ex) {
            alert("Export failed", ex.getMessage());
        }
    }

    @FXML
    void onCancel(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void openTransactions() {
        ItemLocationRow row = locTable.getSelectionModel().getSelectedItem();
        if (row == null) { alert("No selection", "Select a location row first."); return; }
        SmReportDataService.CodeName item = itemPicker.getSelectionModel().getSelectedItem();
        if (item == null) return;

        List<ItemTrxRow> trx = smReports.getItemTransactions(session, item.code(), row.locNo());

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Movements — " + item.code() + " @ Loc " + row.locNo());
        stage.setWidth(760);
        stage.setHeight(420);

        TableView<ItemTrxRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ItemTrxRow, String>     cDate = new TableColumn<>("Date");
        TableColumn<ItemTrxRow, String>     cType = new TableColumn<>("Trx Type");
        TableColumn<ItemTrxRow, String>     cSrc  = new TableColumn<>("Source");
        TableColumn<ItemTrxRow, BigDecimal> cQty  = new TableColumn<>("Quantity");
        TableColumn<ItemTrxRow, BigDecimal> cCost = new TableColumn<>("Unit Cost");
        TableColumn<ItemTrxRow, BigDecimal> cVal  = new TableColumn<>("Value");
        TableColumn<ItemTrxRow, String>     cDoc  = new TableColumn<>("Document");
        TableColumn<ItemTrxRow, String>     cRef  = new TableColumn<>("Reference");

        cDate.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().moveDate() != null ? cd.getValue().moveDate().format(DATE_FMT) : ""));
        cType.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().trxType()));
        cSrc .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().source()));
        cQty .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qty()));
        cQty .setCellFactory(c -> amtCell());
        cCost.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().unitCost()));
        cCost.setCellFactory(c -> amtCell());
        cVal .setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().value()));
        cVal .setCellFactory(c -> amtCell());
        cDoc .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().docNo()));
        cRef .setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().ref()));

        cDate.setPrefWidth(75); cType.setPrefWidth(90); cSrc.setPrefWidth(55);
        cQty.setPrefWidth(90);  cCost.setPrefWidth(90); cVal.setPrefWidth(100);
        cDoc.setPrefWidth(110); cRef.setPrefWidth(110);

        table.getColumns().addAll(cDate, cType, cSrc, cQty, cCost, cVal, cDoc, cRef);
        table.setItems(FXCollections.observableArrayList(trx));
        if (trx.isEmpty()) table.setPlaceholder(new Label("No movements for this item at this location."));

        Button close = new Button("Close");
        close.setOnAction(ev -> stage.close());
        HBox footer = new HBox(close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(table, footer);
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    private byte[] buildExcel() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Stock Item Inquiry");
            String[] headers = {"Location", "Location Name", "Qty On Hand", "Value On Hand", "Status"};
            Row hdr = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) hdr.createCell(i).setCellValue(headers[i]);
            int n = 1;
            for (ItemLocationRow r : locTable.getItems()) {
                Row row = sheet.createRow(n++);
                row.createCell(0).setCellValue(s(r.locNo()));
                row.createCell(1).setCellValue(s(r.locName()));
                row.createCell(2).setCellValue(r.qtyOnHand() != null ? r.qtyOnHand().doubleValue() : 0d);
                row.createCell(3).setCellValue(r.valueOnHand() != null ? r.valueOnHand().doubleValue() : 0d);
                row.createCell(4).setCellValue(s(r.status()));
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static String s(String v) { return v == null ? "" : v; }

    private static <T> TableCell<T, BigDecimal> amtCell() {
        return new TableCell<>() {
            @Override protected void updateItem(BigDecimal v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); }
                else { setText(String.format("%,.2f", v)); setStyle("-fx-alignment: CENTER-RIGHT;"); }
            }
        };
    }

    private static String fmt(BigDecimal v) { return v == null ? "0.00" : String.format("%,.2f", v); }

    private void alert(String header, String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle(header); a.setHeaderText(header); a.setContentText(msg);
        a.showAndWait();
    }
}
