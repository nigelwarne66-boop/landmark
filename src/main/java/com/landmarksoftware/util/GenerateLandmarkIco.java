package com.landmarksoftware.util;

import com.landmarksoftware.ui.LandmarkLogo;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot utility — renders the Landmark pin-mark logo at 16/32/48/256 px
 * and writes a multi-size ICO file to deploy/landmark.ico.
 *
 * Run once via:  mvn javafx:run -Pgenerate-ico
 * Output:        deploy/landmark.ico  (committed to VCS, used by jpackage --icon)
 *
 * Does NOT use javafx-swing / SwingFXUtils — converts WritableImage to
 * BufferedImage via PixelReader so no extra dependency is needed.
 */
public class GenerateLandmarkIco extends Application {

    private static final int[] SIZES = { 16, 32, 48, 256 };

    @Override
    public void start(Stage primaryStage) throws Exception {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);

        List<byte[]> pngs = new ArrayList<>();

        for (int size : SIZES) {
            Node icon = LandmarkLogo.iconMark(size);
            // Throwaway scene triggers CSS/layout pass so bounds are correct
            new Scene(new Group(icon));

            WritableImage wi = icon.snapshot(sp, new WritableImage(size, size));
            PixelReader pr = wi.getPixelReader();

            BufferedImage bi = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    bi.setRGB(x, y, pr.getArgb(x, y));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "PNG", baos);
            pngs.add(baos.toByteArray());
            System.out.printf("  %3dx%-3d  %d bytes%n", size, size, baos.size());
        }

        Path out = Path.of("deploy/landmark.ico");
        Files.createDirectories(out.getParent());
        writeIco(out, pngs);
        System.out.println("Written: " + out.toAbsolutePath());
        Platform.exit();
    }

    /**
     * Writes a modern ICO file with embedded PNG data (Windows Vista+ format).
     * Each size gets its own PNG block; 256px entry uses 0 in the directory
     * (Windows convention for 256x256).
     */
    private void writeIco(Path path, List<byte[]> pngs) throws Exception {
        try (DataOutputStream d = new DataOutputStream(new FileOutputStream(path.toFile()))) {
            int count = pngs.size();
            le16(d, 0);        // reserved
            le16(d, 1);        // type: icon
            le16(d, count);

            int offset = 6 + count * 16;
            for (int i = 0; i < count; i++) {
                int sz  = SIZES[i];
                int len = pngs.get(i).length;
                d.writeByte(sz == 256 ? 0 : sz);   // width  (0 = 256)
                d.writeByte(sz == 256 ? 0 : sz);   // height
                d.writeByte(0);                     // colour count (0 = no palette)
                d.writeByte(0);                     // reserved
                le16(d, 1);                         // colour planes
                le16(d, 32);                        // bits per pixel
                le32(d, len);                       // data size
                le32(d, offset);                    // data offset
                offset += len;
            }
            for (byte[] png : pngs) d.write(png);
        }
    }

    private static void le16(DataOutputStream d, int v) throws Exception {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
    }
    private static void le32(DataOutputStream d, int v) throws Exception {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
        d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }

    public static void main(String[] args) { launch(args); }
}
