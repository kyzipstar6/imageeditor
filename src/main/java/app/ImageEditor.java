package app;

import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils; // needs --add-modules javafx.swing
import javafx.geometry.Insets;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Image editor (pure JavaFX + AWT BufferedImage)
 * Features:
 * - Load image
 * - Simple background crop from corners (uniform bg)
 * - Seed-based crop: click inside object to keep
 * - Toggle mask preview
 * - Save PNG (with alpha)
 */
public class ImageEditor extends Application {

    private ImageView imageView;
    private BufferedImage originalImage; // last loaded image
    private BufferedImage previewImage; // last processed image
    private boolean[][] lastMask; // background mask (true = background)

    // UI
    private Button loadBtn, saveBtn;
    private Button simpleCropBtn, seedCropBtn;
    private CheckBox showMaskCheck;
    private Slider toleranceSlider;

    // Recent files
    private ListView<File> recentListView;
    private ObservableList<File> recentFiles;
    private static final int MAX_RECENT = 12;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Image editor"); // app title

        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(1100);
        imageView.setFitHeight(700);

        // Click seed for seed-based crop
        seedCropBtn = new Button("Seed crop (click)");
        imageView.setOnMouseClicked(e -> {
            if (originalImage == null)
                return;
            if (e.getButton() == MouseButton.PRIMARY && !seedCropBtn.isDisabled()) {
                // Robust mapping: scene -> ImageView local -> image pixel
                var fxImg = imageView.getImage();
                if (fxImg == null)
                    return;
                Point2D local = imageView.sceneToLocal(e.getSceneX(), e.getSceneY());
                Bounds b = imageView.getBoundsInLocal();
                if (local.getX() < 0 || local.getY() < 0 ||
                        local.getX() > b.getWidth() || local.getY() > b.getHeight()) {
                    return; // click outside rendered image area
                }
                double scaleX = fxImg.getWidth() / b.getWidth();
                double scaleY = fxImg.getHeight() / b.getHeight();
                int px = (int) Math.floor(local.getX() * scaleX);
                int py = (int) Math.floor(local.getY() * scaleY);
                if (px >= 0 && py >= 0 && px < originalImage.getWidth() && py < originalImage.getHeight()) {
                    int tol = (int) toleranceSlider.getValue();
                    previewImage = seedBasedCrop(originalImage, px, py, tol);
                    updateImageView(previewImage);
                }
            }
        });

        loadBtn = new Button("Load");
        saveBtn = new Button("Save PNG");

        simpleCropBtn = new Button("Simple crop (corners)");

        showMaskCheck = new CheckBox("Show mask");
        toleranceSlider = new Slider(0, 200, 60);
        toleranceSlider.setPrefWidth(160);
        var tolLabel = new Label("Tolerance:");

        loadBtn.setOnAction(e -> loadImage(stage));
        saveBtn.setOnAction(e -> saveImage(stage));

        simpleCropBtn.setOnAction(e -> {
            if (originalImage == null)
                return;
            int tol = (int) toleranceSlider.getValue();
            previewImage = simpleBackgroundRemoval(originalImage, tol);
            updateImageView(previewImage);
        });

        seedCropBtn.setOnAction(e -> {
            if (originalImage == null)
                return;
            new Alert(Alert.AlertType.INFORMATION,
                    "Click inside the object to KEEP.\nTolerance controls color similarity for the flood-fill.",
                    ButtonType.OK).showAndWait();
        });

        showMaskCheck.setOnAction(e -> {
            if (lastMask == null)
                return;
            if (showMaskCheck.isSelected()) {
                updateImageView(maskToDebugImage(lastMask));
            } else {
                updateImageView(previewImage != null ? previewImage : originalImage);
            }
        });

        var bar = new HBox(8, loadBtn, saveBtn,
                new Separator(), simpleCropBtn, seedCropBtn,
                new Separator(), tolLabel, toleranceSlider, showMaskCheck);
        bar.setPadding(new Insets(8));

        // Recent files UI
        recentFiles = FXCollections.observableArrayList();
        recentListView = new ListView<>(recentFiles);
        recentListView.setPrefWidth(240);
        recentListView.setPlaceholder(new Label("No recent files"));
        recentListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item.getName());
                    setTooltip(new Tooltip(item.getAbsolutePath()));
                }
            }
        });
        recentListView.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                File sel = recentListView.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    openImageFile(sel);
                }
            }
        });

        var recentHeader = new Label("Recent Files");
        recentHeader.setPadding(new Insets(4, 0, 4, 0));
        var clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> recentFiles.clear());
        var headerRow = new HBox(8, recentHeader, clearBtn);
        var recentBox = new VBox(6, headerRow, recentListView);
        recentBox.setPadding(new Insets(8));

        var root = new BorderPane();
        root.setTop(bar);
        root.setLeft(recentBox);
        root.setCenter(new StackPane(imageView));

        stage.setScene(new Scene(root, 1200, 800));
        stage.show();
    }

    /* ========================== IO & UI helpers ========================== */

    private void loadImage(Stage stage) {
        var fc = new FileChooser();
        fc.setTitle("Open image");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File f = fc.showOpenDialog(stage);
        if (f == null)
            return;
        openImageFile(f);
    }

    private void openImageFile(File f) {
        try {
            originalImage = ImageIO.read(f);
            if (originalImage == null) {
                showError("Unsupported or unreadable image: " + f.getName());
                return;
            }
            previewImage = null;
            lastMask = null;
            showMaskCheck.setSelected(false);
            updateImageView(originalImage);
            addToRecent(f);
        } catch (IOException ex) {
            showError("Cannot read image: " + ex.getMessage());
        }
    }

    private void addToRecent(File f) {
        // Move to top, dedupe, cap size
        List<File> filtered = recentFiles.stream()
                .filter(existing -> !existing.equals(f))
                .collect(Collectors.toList());
        filtered.add(0, f);
        if (filtered.size() > MAX_RECENT) {
            filtered = filtered.subList(0, MAX_RECENT);
        }
        recentFiles.setAll(filtered);
        recentListView.getSelectionModel().select(f);
    }

    private void saveImage(Stage stage) {
        if (previewImage == null && originalImage == null)
            return;
        var fc = new FileChooser();
        fc.setTitle("Save PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File f = fc.showSaveDialog(stage);
        if (f == null)
            return;
        try {
            BufferedImage toSave = (previewImage != null) ? previewImage : originalImage;
            ImageIO.write(toSave, "PNG", f);
        } catch (IOException ex) {
            showError("Cannot save: " + ex.getMessage());
        }
    }

    private void updateImageView(BufferedImage img) {
        if (img == null) {
            imageView.setImage(null);
            return;
        }
        WritableImage fx = SwingFXUtils.toFXImage(img, null);
        imageView.setImage(fx);
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }

    /* ========================== Pure-Java methods ========================== */

    // 1) Background removal by sampling corners and flood-filling similar colors as
    // background.
    private BufferedImage simpleBackgroundRemoval(BufferedImage src, int tolerance) {
        int w = src.getWidth(), h = src.getHeight();
        boolean[][] mask = new boolean[w][h]; // true = background

        Color bg = sampleBackgroundColor(src);
        floodFillBackground(src, 0, 0, bg, tolerance, mask);
        floodFillBackground(src, w - 1, 0, bg, tolerance, mask);
        floodFillBackground(src, 0, h - 1, bg, tolerance, mask);
        floodFillBackground(src, w - 1, h - 1, bg, tolerance, mask);

        lastMask = mask;
        return composeTransparent(src, mask);
    }

    // 2) Seed-based crop: user clicks inside the object to keep; flood-fill similar
    // colors = foreground.
    private BufferedImage seedBasedCrop(BufferedImage src, int sx, int sy, int tol) {
        int w = src.getWidth(), h = src.getHeight();
        boolean[][] fg = new boolean[w][h]; // true = foreground
        floodFillForeground(src, sx, sy, tol, fg);

        // convert to background mask (true=background)
        boolean[][] bgMask = new boolean[w][h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bgMask[x][y] = !fg[x][y];
        lastMask = bgMask;
        return composeTransparent(src, bgMask);
    }

    private void floodFillBackground(BufferedImage img, int sx, int sy, Color bg, int tol, boolean[][] mask) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] seen = new boolean[w][h];
        var q = new ArrayDeque<int[]>();
        if (sx < 0 || sy < 0 || sx >= w || sy >= h)
            return;
        q.add(new int[] { sx, sy });
        while (!q.isEmpty()) {
            int[] p = q.remove();
            int x = p[0], y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h)
                continue;
            if (seen[x][y])
                continue;
            seen[x][y] = true;

            int rgb = img.getRGB(x, y);
            int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
            int dr = rr - bg.getRed(), dg = gg - bg.getGreen(), db = bb - bg.getBlue();
            int dist = Math.abs(dr) + Math.abs(dg) + Math.abs(db);
            if (dist <= tol) {
                mask[x][y] = true;
                q.add(new int[] { x + 1, y });
                q.add(new int[] { x - 1, y });
                q.add(new int[] { x, y + 1 });
                q.add(new int[] { x, y - 1 });
            }
        }
    }

    private void floodFillForeground(BufferedImage img, int sx, int sy, int tol, boolean[][] mask) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] seen = new boolean[w][h];
        var q = new ArrayDeque<int[]>();
        if (sx < 0 || sy < 0 || sx >= w || sy >= h)
            return;
        int seed = img.getRGB(sx, sy);
        int sr = (seed >> 16) & 0xFF, sg = (seed >> 8) & 0xFF, sb = seed & 0xFF;
        q.add(new int[] { sx, sy });
        while (!q.isEmpty()) {
            int[] p = q.remove();
            int x = p[0], y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h)
                continue;
            if (seen[x][y])
                continue;
            seen[x][y] = true;

            int rgb = img.getRGB(x, y);
            int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
            int dr = rr - sr, dg = gg - sg, db = bb - sb;
            int dist = Math.abs(dr) + Math.abs(dg) + Math.abs(db);
            if (dist <= tol) {
                mask[x][y] = true;
                q.add(new int[] { x + 1, y });
                q.add(new int[] { x - 1, y });
                q.add(new int[] { x, y + 1 });
                q.add(new int[] { x, y - 1 });
            }
        }
    }

    private Color sampleBackgroundColor(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = { img.getRGB(0, 0), img.getRGB(w - 1, 0), img.getRGB(0, h - 1), img.getRGB(w - 1, h - 1) };
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        return new Color((int) (r / 4), (int) (g / 4), (int) (b / 4));
    }

    private BufferedImage composeTransparent(BufferedImage src, boolean[][] bgMask) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y) & 0x00FFFFFF;
                if (bgMask[x][y])
                    out.setRGB(x, y, rgb); // alpha 0
                else
                    out.setRGB(x, y, (0xFF << 24) | rgb);
            }
        }
        return out;
    }

    private BufferedImage maskToDebugImage(boolean[][] mask) {
        int w = mask.length, h = mask[0].length;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mask[x][y])
                    out.setRGB(x, y, 0x80FF0000); // semi red = background
                else
                    out.setRGB(x, y, 0x8000FF00); // semi green = foreground
            }
        }
        return out;
    }
}
