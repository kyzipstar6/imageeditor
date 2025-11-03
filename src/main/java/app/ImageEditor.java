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
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private Button backBtn, forwardBtn;
    private CheckBox showMaskCheck, chatModeCheck, drawingModeCheck, selectionModeCheck;
    private Slider toleranceSlider;
    
    // Drawing mode components
    private javafx.scene.canvas.Canvas drawCanvas;
    private javafx.scene.canvas.GraphicsContext drawGC;
    private List<Point2D> currentPath;
    private boolean isDrawing = false;
    
    // Selection mode components
    private boolean isSelecting = false;
    private double selStartCanvasX, selStartCanvasY, selEndCanvasX, selEndCanvasY;
    private java.awt.Rectangle selectionRect; // in image pixel coordinates
    private Button clearSelectionBtn;

    // Chat mode components
    private javafx.scene.control.TextArea chatInput;
    private javafx.scene.control.TextArea chatHistory;
    private Button sendButton;
    private VBox chatBox;
    private List<String> chatLogs = new ArrayList<>();
    private static final String CHAT_LOG_FILE = "chat_history.txt";

    // Recent files
    private ListView<File> recentListView;
    private ObservableList<File> recentFiles;
    private static final int MAX_RECENT = 12;

    // History (Undo/Redo)
    private ArrayDeque<BufferedImage> undoStack = new ArrayDeque<>();
    private ArrayDeque<BufferedImage> redoStack = new ArrayDeque<>();
    private static final int MAX_HISTORY = 15;

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
        
        // Initialize drawing canvas
        drawCanvas = new javafx.scene.canvas.Canvas(1100, 700);
        drawCanvas.setMouseTransparent(true); // Initially pass events through to imageView
        drawGC = drawCanvas.getGraphicsContext2D();
        currentPath = new ArrayList<>();

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
                    BufferedImage newImg = seedBasedCrop(originalImage, px, py, tol);
                    if (selectionRect != null) {
                        newImg = combineWithSelection(originalImage, newImg, selectionRect);
                    }
                    applyNewImage(newImg);
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
            BufferedImage newImg = simpleBackgroundRemoval(originalImage, tol);
            if (selectionRect != null) {
                newImg = combineWithSelection(originalImage, newImg, selectionRect);
            }
            applyNewImage(newImg);
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

        chatModeCheck = new CheckBox("Chat Mode");
        chatModeCheck.setOnAction(e -> toggleChatMode());
        
        drawingModeCheck = new CheckBox("Drawing Mode");
        drawingModeCheck.setOnAction(e -> toggleDrawingMode());

        selectionModeCheck = new CheckBox("Selection Mode");
        selectionModeCheck.setOnAction(e -> toggleSelectionMode());
        clearSelectionBtn = new Button("Clear Selection");
        clearSelectionBtn.setOnAction(e -> clearSelection());
        
        // History buttons
        backBtn = new Button("Back");
        backBtn.setOnAction(e -> undo());
        forwardBtn = new Button("Forward");
        forwardBtn.setOnAction(e -> redo());
        updateHistoryButtons();
        
        var bar = new HBox(8, loadBtn, saveBtn,
                new Separator(), simpleCropBtn, seedCropBtn,
                new Separator(), backBtn, forwardBtn,
                new Separator(), tolLabel, toleranceSlider, showMaskCheck,
                new Separator(), drawingModeCheck, selectionModeCheck, clearSelectionBtn, chatModeCheck);
        bar.setPadding(new Insets(8));
        
        // Initialize chat components
        chatInput = new javafx.scene.control.TextArea();
        chatInput.setPrefRowCount(3);
        chatInput.setPromptText("Enter your image editing command...");
        chatInput.setWrapText(true);
        
        chatHistory = new javafx.scene.control.TextArea();
        chatHistory.setPrefRowCount(10);
        chatHistory.setEditable(false);
        chatHistory.setWrapText(true);
        
        sendButton = new Button("Send");
        sendButton.setOnAction(e -> handleChatCommand());
        
        chatBox = new VBox(8, chatHistory, chatInput, sendButton);
        chatBox.setPadding(new Insets(8));
        chatBox.setVisible(false);

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
        VBox recchatBox = new VBox(8, recentBox, chatBox);
        // Create a stack pane for image and drawing canvas
        var imageStack = new StackPane(imageView, drawCanvas);
        
        var root = new BorderPane();
        root.setTop(bar);
        root.setLeft(recchatBox);
        root.setCenter(imageStack);
        //root.setRight(chatBox);

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
            clearHistory();
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

    /* ========================== History (Undo/Redo) ========================== */

    private BufferedImage getCurrentImage() {
        return (previewImage != null) ? previewImage : originalImage;
    }

    private void applyNewImage(BufferedImage newImage) {
        if (newImage == null) return;
        BufferedImage current = getCurrentImage();
        if (current != null) {
            if (undoStack.size() >= MAX_HISTORY) undoStack.removeFirst();
            undoStack.addLast(current);
        }
        redoStack.clear();
        previewImage = newImage;
        showMaskCheck.setSelected(false);
        lastMask = (lastMask != null) ? lastMask : null; // placeholder to keep compiler calm
        updateImageView(previewImage);
        updateHistoryButtons();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        BufferedImage current = getCurrentImage();
        if (current != null) {
            if (redoStack.size() >= MAX_HISTORY) redoStack.removeFirst();
            redoStack.addLast(current);
        }
        BufferedImage prev = undoStack.removeLast();
        previewImage = prev;
        showMaskCheck.setSelected(false);
        lastMask = null;
        updateImageView(previewImage);
        updateHistoryButtons();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        BufferedImage current = getCurrentImage();
        if (current != null) {
            if (undoStack.size() >= MAX_HISTORY) undoStack.removeFirst();
            undoStack.addLast(current);
        }
        BufferedImage nxt = redoStack.removeLast();
        previewImage = nxt;
        showMaskCheck.setSelected(false);
        lastMask = null;
        updateImageView(previewImage);
        updateHistoryButtons();
    }

    private void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        if (backBtn != null) backBtn.setDisable(undoStack.isEmpty());
        if (forwardBtn != null) forwardBtn.setDisable(redoStack.isEmpty());
    }
    
    private void toggleChatMode() {
        boolean enabled = chatModeCheck.isSelected();
        chatBox.setVisible(enabled);
        if (enabled) {
            loadChatHistory();
        } else {
            saveChatHistory();
        }
    }
    
    private void handleChatCommand() {
        String command = chatInput.getText().trim();
        if (command.isEmpty()) return;
        
        // Add command to history
        String entry = "> " + command + "\n";
        chatHistory.appendText(entry);
        chatLogs.add(entry);
        
        // Process command
        processImageCommand(command);
        
        // Clear input
        chatInput.clear();
    }
    
    private void processImageCommand(String rawCommand) {
        String command = rawCommand.toLowerCase().trim();

        // Add response to chat
        String response = "Processing: " + command + "\n";
        chatHistory.appendText(response);
        chatLogs.add(response);

        try {
            // 0) Help / hints (no image required)
            if (containsAny(command, new String[]{"help","?","commands"})) {
                addChatResponse(
                    "Commands: open <file>, save <file>, remove background, detect shapes, seed <x> <y>, show mask, hide mask, set tolerance <n>, increase tolerance <n>, decrease tolerance <n>, enable drawing, disable drawing, reset preview"
                );
                return;
            }

            // 1) Open/Load image from path (quoted or unquoted)
            if (startsWithAny(command, new String[]{"open ", "load ", "open", "load:\""})) {
                String path = extractPathAfterKeyword(rawCommand, new String[]{"open", "load"});
                if (path == null || path.isEmpty()) {
                    addChatResponse("Please provide a file path, e.g., open C:/path/image.png");
                    return;
                }
                File f = new File(path);
                if (!f.exists()) {
                    addChatResponse("File not found: " + f.getAbsolutePath());
                    return;
                }
                openImageFile(f);
                addChatResponse("Opened: " + f.getName());
                return;
            }

            // 2) Save image (optional path)
            if (startsWithAny(command, new String[]{"save ", "export ", "save", "export"}) || command.equals("save") || command.equals("export")) {
                if (previewImage == null && originalImage == null) {
                    addChatResponse("Nothing to save. Load or process an image first.");
                    return;
                }
                String path = extractPathAfterKeyword(rawCommand, new String[]{"save", "export"});
                if (path == null || path.isEmpty()) {
                    addChatResponse("Please provide a .png path, e.g., save C:/path/out.png");
                    return;
                }
                try {
                    File out = new File(path);
                    if (!out.getName().toLowerCase().endsWith(".png")) {
                        File out2 = out;
                        String strs = out2.getParentFile() == null ? "." : out2.getParentFile() + File.separator + out2.getName() + ".png";
                        out = new File(strs);
                    }
                    BufferedImage toSave = (previewImage != null) ? previewImage : originalImage;
                    ImageIO.write(toSave, "PNG", out);
                    addChatResponse("Saved: " + out.getAbsolutePath());
                } catch (IOException ioe) {
                    addChatResponse("Save failed: " + ioe.getMessage());
                }
                return;
            }

            // For the remaining commands, ensure an image is loaded
            if (originalImage == null) {
                addChatResponse("Please load an image first (e.g., open <file>). ");
                return;
            }

            // 3) Tolerance controls
            if (containsAny(command, new String[]{"set tolerance", "tolerance"})) {
                Integer n = extractFirstInteger(command);
                if (n != null) {
                    int clamped = Math.max(0, Math.min(200, n));
                    toleranceSlider.setValue(clamped);
                    addChatResponse("Tolerance set to " + clamped);
                } else {
                    addChatResponse("Specify a number, e.g., set tolerance 80");
                }
                return;
            }
            if (containsAny(command, new String[]{"increase tolerance", "raise tolerance", "more tolerance"})) {
                Integer by = extractFirstInteger(command);
                if (by == null) by = 10;
                int clamped = (int)Math.max(0, Math.min(200, toleranceSlider.getValue() + by));
                toleranceSlider.setValue(clamped);
                addChatResponse("Tolerance increased to " + clamped);
                return;
            }
            if (containsAny(command, new String[]{"decrease tolerance", "lower tolerance", "less tolerance"})) {
                Integer by = extractFirstInteger(command);
                if (by == null) by = 10;
                int clamped = (int)Math.max(0, Math.min(200, toleranceSlider.getValue() - by));
                toleranceSlider.setValue(clamped);
                addChatResponse("Tolerance decreased to " + clamped);
                return;
            }

            // 4) Mask visibility
            if (containsAny(command, new String[]{"show mask", "enable mask", "mask on"})) {
                if (lastMask == null) {
                    addChatResponse("No mask available yet. Try remove background or seed crop.");
                } else {
                    showMaskCheck.setSelected(true);
                    updateImageView(maskToDebugImage(lastMask));
                    addChatResponse("Mask shown.");
                }
                return;
            }
            if (containsAny(command, new String[]{"hide mask", "disable mask", "mask off"})) {
                showMaskCheck.setSelected(false);
                updateImageView(previewImage != null ? previewImage : originalImage);
                addChatResponse("Mask hidden.");
                return;
            }

            // 5) Drawing mode
            if (containsAny(command, new String[]{"enable drawing", "drawing on", "start drawing"})) {
                drawingModeCheck.setSelected(true);
                toggleDrawingMode();
                addChatResponse("Drawing mode enabled.");
                return;
            }
            if (containsAny(command, new String[]{"disable drawing", "drawing off", "stop drawing"})) {
                drawingModeCheck.setSelected(false);
                toggleDrawingMode();
                addChatResponse("Drawing mode disabled.");
                return;
            }

            // 6) Background removal (simple corners)
            if (containsAny(command, new String[]{"remove background", "erase background", "make background transparent", "background remove", "crop background"})) {
                int tol = (int) toleranceSlider.getValue();
                BufferedImage newImg = simpleBackgroundRemoval(originalImage, tol);
                if (selectionRect != null) {
                    newImg = combineWithSelection(originalImage, newImg, selectionRect);
                }
                applyNewImage(newImg);
                addChatResponse("Background removed using tolerance " + tol + ".");
                return;
            }

            // 7) Seed crop with coordinates: "seed 120 200" or "crop at 120,200"
            Matcher mSeed = Pattern.compile("(seed|click|crop at)\\s*(?:x)?\\s*(\\d+)\\s*(?:,|\\s)+(?:y)?\\s*(\\d+)").matcher(command);
            if (mSeed.find()) {
                int px = Integer.parseInt(mSeed.group(2));
                int py = Integer.parseInt(mSeed.group(3));
                int tol = (int) toleranceSlider.getValue();
                px = Math.max(0, Math.min(originalImage.getWidth()-1, px));
                py = Math.max(0, Math.min(originalImage.getHeight()-1, py));
                BufferedImage newImg = seedBasedCrop(originalImage, px, py, tol);
                if (selectionRect != null) {
                    newImg = combineWithSelection(originalImage, newImg, selectionRect);
                }
                applyNewImage(newImg);
                addChatResponse("Seed crop at (" + px + ", " + py + ") with tolerance " + tol + ".");
                return;
            }

            // 8) Detect shapes
            if (containsAny(command, new String[]{"detect", "find", "detect shapes", "find shapes"})) {
                detectAndHighlightShapes();
                return;
            }

            // 9) Reset preview
            if (containsAny(command, new String[]{"reset", "revert", "original", "clear preview"})) {
                // push current to undo and show original
                BufferedImage current = getCurrentImage();
                if (current != null) {
                    if (undoStack.size() >= MAX_HISTORY) undoStack.removeFirst();
                    undoStack.addLast(current);
                }
                redoStack.clear();
                previewImage = null; // show original
                showMaskCheck.setSelected(false);
                lastMask = null;
                updateImageView(originalImage);
                updateHistoryButtons();
                addChatResponse("Preview reset to original image.");
                return;
            }

            // 10) Placeholders for future features
            if (containsAny(command, new String[]{"combine", "merge"})) {
                addChatResponse("Shape combining not implemented yet.");
                return;
            }
            if (containsAny(command, new String[]{"split", "separate"})) {
                addChatResponse("Shape splitting not implemented yet.");
                return;
            }

            addChatResponse("Unknown command. Type 'help' for options.");
        } catch (Exception e) {
            addChatResponse("Error: " + e.getMessage());
        }
    }

    private boolean containsAny(String text, String[] needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    private boolean startsWithAny(String text, String[] needles) {
        for (String n : needles) {
            if (text.startsWith(n)) return true;
        }
        return false;
    }

    private Integer extractFirstInteger(String text) {
        Matcher m = Pattern.compile("(-?\\d+)").matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String extractPathAfterKeyword(String raw, String[] keys) {
        // Preserve original casing and quotes; search case-insensitively for keyword then take rest
        String r = raw.trim();
        int idx = -1; String keyFound = null;
        for (String k : keys) {
            int i = r.toLowerCase().indexOf(k.toLowerCase());
            if (i == 0) { idx = k.length(); keyFound = k; break; }
        }
        if (idx < 0) return null;
        String tail = r.substring(idx).trim();
        if (tail.startsWith(":")) tail = tail.substring(1).trim();
        if (tail.startsWith("\"") && tail.endsWith("\"")) {
            return tail.substring(1, tail.length()-1);
        }
        if (tail.startsWith("'") && tail.endsWith("'")) {
            return tail.substring(1, tail.length()-1);
        }
        return tail;
    }
    
    private void addChatResponse(String message) {
        String response = "System: " + message + "\n";
        chatHistory.appendText(response);
        chatLogs.add(response);
    }
    
    private void detectAndHighlightShapes() {
        // Use the existing flood fill algorithm with multiple seed points
        if (originalImage == null) return;
        
        int w = originalImage.getWidth();
        int h = originalImage.getHeight();
        boolean[][] visited = new boolean[w][h];
        List<Point> seeds = findPotentialShapeSeeds(originalImage);
        
        if (seeds.isEmpty()) {
            addChatResponse("No distinct shapes detected.");
            return;
        }
        
        // Use existing seed-based crop for each detected seed point
        int tol = (int) toleranceSlider.getValue();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(originalImage, 0, 0, null);
        
        for (Point seed : seeds) {
            if (!visited[seed.x][seed.y]) {
                BufferedImage shape = seedBasedCrop(originalImage, seed.x, seed.y, tol);
                g.drawImage(shape, 0, 0, null);
            }
        }
        g.dispose();
        
        applyNewImage(result);
        addChatResponse("Detected " + seeds.size() + " potential shapes.");
    }
    
    private List<Point> findPotentialShapeSeeds(BufferedImage img) {
        List<Point> seeds = new ArrayList<>();
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(w, h) / 20; // Sample every 5% of the image
        
        for (int y = step; y < h - step; y += step) {
            for (int x = step; x < w - step; x += step) {
                if (isLocalColorDifference(img, x, y, step)) {
                    seeds.add(new Point(x, y));
                }
            }
        }
        return seeds;
    }
    
    private boolean isLocalColorDifference(BufferedImage img, int x, int y, int step) {
        int center = img.getRGB(x, y);
        int top = img.getRGB(x, Math.max(0, y - step));
        int bottom = img.getRGB(x, Math.min(img.getHeight() - 1, y + step));
        int left = img.getRGB(Math.max(0, x - step), y);
        int right = img.getRGB(Math.min(img.getWidth() - 1, x + step), y);
        
        return colorDifference(center, top) > 30 ||
               colorDifference(center, bottom) > 30 ||
               colorDifference(center, left) > 30 ||
               colorDifference(center, right) > 30;
    }
    
    private int colorDifference(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;
        
        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
    }
    
    private void loadChatHistory() {
        try {
            File file = new File(CHAT_LOG_FILE);
            if (file.exists()) {
                chatLogs = Files.readAllLines(file.toPath());
                chatHistory.clear();
                for (String log : chatLogs) {
                    chatHistory.appendText(log + "\n");
                }
            }
        } catch (IOException e) {
            showError("Could not load chat history: " + e.getMessage());
        }
    }
    
    private void saveChatHistory() {
        try {
            Files.write(new File(CHAT_LOG_FILE).toPath(), chatLogs);
        } catch (IOException e) {
            showError("Could not save chat history: " + e.getMessage());
        }
    }
    
    private void toggleSelectionMode() {
        boolean enabled = selectionModeCheck.isSelected();
        // Disable drawing when selection is enabled to avoid conflicts
        if (enabled && drawingModeCheck.isSelected()) {
            drawingModeCheck.setSelected(false);
            toggleDrawingMode();
        }
        drawCanvas.setMouseTransparent(!enabled);
        if (enabled) {
            setupSelectionHandlers();
            // Visual style
            drawGC.setStroke(javafx.scene.paint.Color.CORNFLOWERBLUE);
            drawGC.setLineWidth(2);
            // Redraw existing selection if any
            drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
            if (selectionRect != null) {
                // approximate rectangle back to canvas by using stored last canvas coords if available
                drawSelectionOverlay();
            }
        } else {
            // Clear handlers and overlay
            drawCanvas.setOnMousePressed(null);
            drawCanvas.setOnMouseDragged(null);
            drawCanvas.setOnMouseReleased(null);
            drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        }
    }

    private void setupSelectionHandlers() {
        drawCanvas.setOnMousePressed(e -> {
            if (originalImage == null) return;
            isSelecting = true;
            selStartCanvasX = e.getX();
            selStartCanvasY = e.getY();
            selEndCanvasX = selStartCanvasX;
            selEndCanvasY = selStartCanvasY;
            // Initialize selectionRect in image coordinates
            Point2D imgPoint = convertToImageCoordinates(e.getX(), e.getY());
            int ix = (int) Math.round(imgPoint.getX());
            int iy = (int) Math.round(imgPoint.getY());
            selectionRect = new java.awt.Rectangle(ix, iy, 0, 0);
            drawSelectionOverlay();
        });

        drawCanvas.setOnMouseDragged(e -> {
            if (!isSelecting) return;
            selEndCanvasX = e.getX();
            selEndCanvasY = e.getY();
            // Update rect in image coordinates
            Point2D p1 = convertToImageCoordinates(selStartCanvasX, selStartCanvasY);
            Point2D p2 = convertToImageCoordinates(selEndCanvasX, selEndCanvasY);
            int x1 = (int) Math.floor(Math.min(p1.getX(), p2.getX()));
            int y1 = (int) Math.floor(Math.min(p1.getY(), p2.getY()));
            int x2 = (int) Math.ceil(Math.max(p1.getX(), p2.getX()));
            int y2 = (int) Math.ceil(Math.max(p1.getY(), p2.getY()));
            int w = Math.max(0, x2 - x1);
            int h = Math.max(0, y2 - y1);
            selectionRect = new java.awt.Rectangle(x1, y1, w, h);
            drawSelectionOverlay();
        });

        drawCanvas.setOnMouseReleased(e -> {
            if (!isSelecting) return;
            isSelecting = false;
            selEndCanvasX = e.getX();
            selEndCanvasY = e.getY();
            // Finalize image-space rectangle
            Point2D p1 = convertToImageCoordinates(selStartCanvasX, selStartCanvasY);
            Point2D p2 = convertToImageCoordinates(selEndCanvasX, selEndCanvasY);
            int x1 = (int) Math.floor(Math.min(p1.getX(), p2.getX()));
            int y1 = (int) Math.floor(Math.min(p1.getY(), p2.getY()));
            int x2 = (int) Math.ceil(Math.max(p1.getX(), p2.getX()));
            int y2 = (int) Math.ceil(Math.max(p1.getY(), p2.getY()));
            int w = Math.max(0, x2 - x1);
            int h = Math.max(0, y2 - y1);
            selectionRect = new java.awt.Rectangle(x1, y1, w, h);
            drawSelectionOverlay();
        });
    }

    private void drawSelectionOverlay() {
        drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        double x = Math.min(selStartCanvasX, selEndCanvasX);
        double y = Math.min(selStartCanvasY, selEndCanvasY);
        double w = Math.abs(selEndCanvasX - selStartCanvasX);
        double h = Math.abs(selEndCanvasY - selStartCanvasY);
        if (w <= 0 || h <= 0) return;
        // Semi-transparent fill to visualize selection
        drawGC.setGlobalAlpha(0.15);
        drawGC.setFill(javafx.scene.paint.Color.CORNFLOWERBLUE);
        drawGC.fillRect(x, y, w, h);
        drawGC.setGlobalAlpha(1.0);
        drawGC.setStroke(javafx.scene.paint.Color.CORNFLOWERBLUE);
        drawGC.setLineDashes(8);
        drawGC.strokeRect(x, y, w, h);
        drawGC.setLineDashes(0);
    }

    private void clearSelection() {
        selectionRect = null;
        isSelecting = false;
        selStartCanvasX = selStartCanvasY = selEndCanvasX = selEndCanvasY = 0;
        drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
    }

    private BufferedImage combineWithSelection(BufferedImage original, BufferedImage processed, java.awt.Rectangle sel) {
        if (original == null || processed == null || sel == null) return processed;
        int w = original.getWidth();
        int h = original.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (sel.contains(x, y)) {
                    out.setRGB(x, y, processed.getRGB(x, y));
                } else {
                    out.setRGB(x, y, original.getRGB(x, y));
                }
            }
        }
        return out;
    }

    private void toggleDrawingMode() {
        boolean enabled = drawingModeCheck.isSelected();
        drawCanvas.setMouseTransparent(!enabled);
        if (enabled) {
            setupDrawingHandlers();
            drawGC.setStroke(javafx.scene.paint.Color.RED);
            drawGC.setLineWidth(2);
            drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        } else {
            clearDrawing();
        }
    }
    
    private void setupDrawingHandlers() {
        drawCanvas.setOnMousePressed(e -> {
            if (originalImage == null) return;
            isDrawing = true;
            currentPath.clear();
            Point2D imgPoint = convertToImageCoordinates(e.getX(), e.getY());
            currentPath.add(imgPoint);
            drawGC.beginPath();
            drawGC.moveTo(e.getX(), e.getY());
        });
        
        drawCanvas.setOnMouseDragged(e -> {
            if (!isDrawing) return;
            Point2D imgPoint = convertToImageCoordinates(e.getX(), e.getY());
            currentPath.add(imgPoint);
            drawGC.lineTo(e.getX(), e.getY());
            drawGC.stroke();
        });
        
        drawCanvas.setOnMouseReleased(e -> {
            if (!isDrawing) return;
            isDrawing = false;
            Point2D imgPoint = convertToImageCoordinates(e.getX(), e.getY());
            currentPath.add(imgPoint);
            drawGC.lineTo(e.getX(), e.getY());
            drawGC.stroke();
            processDrawnShape();
        });
    }
    
    private Point2D convertToImageCoordinates(double x, double y) {
        var fxImg = imageView.getImage();
        if (fxImg == null) return new Point2D(x, y);
        
        Point2D local = imageView.sceneToLocal(x, y);
        Bounds b = imageView.getBoundsInLocal();
        
        double scaleX = fxImg.getWidth() / b.getWidth();
        double scaleY = fxImg.getHeight() / b.getHeight();
        
        return new Point2D(
            Math.max(0, Math.min(fxImg.getWidth() - 1, local.getX() * scaleX)),
            Math.max(0, Math.min(fxImg.getHeight() - 1, local.getY() * scaleY))
        );
    }
    
    private void clearDrawing() {
        if (drawGC != null) {
            drawGC.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        }
        currentPath.clear();
        isDrawing = false;
    }
    
    private void processDrawnShape() {
        if (currentPath.size() < 3 || originalImage == null) return;
        
        // Create a mask from the drawn path
        int w = originalImage.getWidth();
        int h = originalImage.getHeight();
        boolean[][] shapeMask = new boolean[w][h];
        
        // Convert path to polygon
        int[] xPoints = new int[currentPath.size()];
        int[] yPoints = new int[currentPath.size()];
        for (int i = 0; i < currentPath.size(); i++) {
            Point2D p = currentPath.get(i);
            xPoints[i] = (int) p.getX();
            yPoints[i] = (int) p.getY();
        }
        
        // Create polygon for shape detection
        java.awt.Polygon poly = new java.awt.Polygon(xPoints, yPoints, currentPath.size());
        
        // Fill the mask based on the polygon
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (poly.contains(x, y)) {
                    shapeMask[x][y] = true;
                }
            }
        }
        
        // Use the mask to crop the image
        BufferedImage newImg = applyShapeMask(originalImage, shapeMask);
        applyNewImage(newImg);
        
        // Clear the drawing for the next shape
        clearDrawing();
    }
    
    private BufferedImage applyShapeMask(BufferedImage src, boolean[][] shapeMask) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int tol = (int) toleranceSlider.getValue();
        
        // Find seed points inside the shape
        List<Point> seeds = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (shapeMask[x][y] && isLocalColorDifference(src, x, y, 5)) {
                    seeds.add(new Point(x, y));
                }
            }
        }
        
        // Apply flood fill from each seed point
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        
        for (Point seed : seeds) {
            BufferedImage shape = seedBasedCrop(src, seed.x, seed.y, tol);
            g.drawImage(shape, 0, 0, null);
        }
        g.dispose();
        
        return out;
    }
}
