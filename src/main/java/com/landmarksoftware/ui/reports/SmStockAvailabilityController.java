package com.landmarksoftware.ui.reports;

import com.landmarksoftware.model.AppSession;
import com.landmarksoftware.service.sm.SmReportDataService;
import com.landmarksoftware.service.sm.SmReportDataService.ItemInquiryResult;
import com.landmarksoftware.service.sm.SmReportDataService.ItemLocationRow;
import com.landmarksoftware.ui.ReportsHubController;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
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
import java.util.List;
import java.util.ResourceBundle;

/**
 * SMTI03 — Stock Availability Inquiry.
 *
 * <p>Pick a stock item, see its quantity position across all locations:
 * on hand, allocated (allocated + reserved), available, backordered and on
 * purchase order. Quantities shown in the item's stock unit (the COBOL default
 * WS-DISPLAY-QTYS-IND = 'I').
 */
@Component
@Scope("prototype")
public class SmStockAvailabilityController implements Initializable {

    @Autowired private AppSession session;
    @Autowired private SmReportDataService smReports;
    @Autowired private ReportsHubController hub;

    @FXML private ComboBox<SmReportDataService.CodeName> itemPicker;
    @FXML private CheckBox hideZeroAvail;

    @FXML private HBox itemBar;
    @FXML private Label lblItemDesc;

    @FXML private TableView<ItemLocationRow> locTable;
    @FXML private TableColumn<ItemLocationRow, String>     colLoc;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colOnHand;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colAllocated;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colAvailable;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colBackord;
    @FXML private TableColumn<ItemLocationRow, BigDecimal> colOnPo;
    @FXML private TableColumn<ItemLocationRow, String>     colStatus;

    @FXML private HBox totalsBar;
    @FXML private Label lblTotalOnHand;
    @FXML private Label lblTotalAvail;

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
        colOnHand.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyOnHand()));
        colOnHand.setCellFactory(c -> amtCell());
        colAllocated.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyAllocated()));
        colAllocated.setCellFactory(c -> amtCell());
        colAvailable.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyAvailable()));
        colAvailable.setCellFactory(c -> amtCell());
        colBackord.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyBackord()));
        colBackord.setCellFactory(c -> amtCell());
        colOnPo.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().qtyOnPo()));
        colOnPo.setCellFactory(c -> amtCell());
        colStatus.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));
    }

    @FXML
    void onFilter(ActionEvent e) {
        SmReportDataService.CodeName item = itemPicker.getSelectionModel().getSelectedItem();
        if (item == null || item.code().isBlank()) {
            alert("Select an item", "Please select a stock item before filtering.");
            return;
        }
        ItemInquiryResult result = smReports.getItemInquiry(session, item.code());

        List<ItemLocationRow> rows = result.rows();
        if (hideZeroAvail.isSelected()) {
            rows = rows.stream()
                .filter(r -> r.qtyAvailable() != null && r.qtyAvailable().compareTo(BigDecimal.ZERO) != 0)
                .toList();
        }
        locTable.setItems(FXCollections.observableArrayList(rows));

        lblItemDesc.setText(result.stockCode() + " — " + result.desc1()
            + (result.desc2().isEmpty() ? "" : "  " + result.desc2()));
        itemBar.setVisible(true); itemBar.setManaged(true);

        String unit = result.stockUnit().isEmpty() ? "" : " " + result.stockUnit();
        lblTotalOnHand.setText("Total on hand: " + fmt(result.totalQtyOnHand()) + unit);
        lblTotalAvail.setText("Total available: " + fmt(result.totalAvailable()) + unit);
        totalsBar.setVisible(true); totalsBar.setManaged(true);
    }

    @FXML
    void onExportExcel(ActionEvent e) {
        if (locTable.getItems().isEmpty()) { alert("No data", "Filter an item first."); return; }
        try {
            byte[] data = buildExcel();
            Window owner = ((Node) e.getSource()).getScene().getWindow();
            hub.saveAndOpen(data, "sm-stock-availability", ".xlsx", owner);
        } catch (Exception ex) {
            alert("Export failed", ex.getMessage());
        }
    }

    @FXML
    void onCancel(ActionEvent e) {
        ((Stage) ((Node) e.getSource()).getScene().getWindow()).close();
    }

    private byte[] buildExcel() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Stock Availability");
            String[] headers = {"Location", "On Hand", "Allocated", "Available", "Backordered", "On P/Order", "Status"};
            Row hdr = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) hdr.createCell(i).setCellValue(headers[i]);
            int n = 1;
            for (ItemLocationRow r : locTable.getItems()) {
                Row row = sheet.createRow(n++);
                row.createCell(0).setCellValue(s(r.locNo()));
                row.createCell(1).setCellValue(d(r.qtyOnHand()));
                row.createCell(2).setCellValue(d(r.qtyAllocated()));
                row.createCell(3).setCellValue(d(r.qtyAvailable()));
                row.createCell(4).setCellValue(d(r.qtyBackord()));
                row.createCell(5).setCellValue(d(r.qtyOnPo()));
                row.createCell(6).setCellValue(s(r.status()));
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static double d(BigDecimal v) { return v != null ? v.doubleValue() : 0d; }
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
