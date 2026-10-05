package com.dip.restoration.ui;

import com.dip.restoration.filters.ImageFilters;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Stack;

/**
 * MainFrame
 * ---------
 * The application window. Holds:
 *   - originalImage : the untouched image as loaded from disk (for Reset)
 *   - currentImage   : the working (committed) image shown in Preview
 *   - undoStack      : every committed state of currentImage, for global Undo
 *
 * Slider-based filters (Brightness/Contrast, Color) work "live": moving the
 * slider previews the effect instantly from a captured base image, and the
 * change is only committed to currentImage / undoStack once, when the user
 * lets go of the slider. Each slider group also has its own Reset button
 * that cancels an in-progress adjustment without disturbing earlier filters.
 */
public class MainFrame extends JFrame {

    // ---- image state ----
    private BufferedImage originalImage;
    private BufferedImage currentImage;
    private final Stack<BufferedImage> undoStack = new Stack<>();
    private File lastSavedFile;

    // ---- display state ----
    private double zoomFactor = 1.0;
    private boolean originalHidden = false;

    // ---- live-adjustment state (brightness/contrast) ----
    private BufferedImage bcBase = null;      // image captured when slider first touched
    private BufferedImage bcPreview = null;   // most recent live preview (not yet committed)

    // ---- live-adjustment state (color) ----
    private BufferedImage colorBase = null;
    private BufferedImage colorPreview = null;

    // ---- UI components ----
    private final JLabel originalLabel = new JLabel("Open an image to begin", SwingConstants.CENTER);
    private final JLabel previewLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("No image loaded.");
    private final JLabel zoomLabel = new JLabel("100%");

    private JPanel imagesPanel;
    private JScrollPane originalScroll;
    private JScrollPane previewScroll;
    private JButton hideOriginalButton;
    private JMenuItem browseItem;

    private final JSlider brightnessSlider = new JSlider(-100, 100, 0);
    private final JSlider contrastSlider = new JSlider(50, 300, 100); // percent, 100 = unchanged
    private final JSlider hueSlider = new JSlider(-180, 180, 0);
    private final JSlider saturationSlider = new JSlider(0, 200, 100); // percent
    private final JSlider kernelSlider = new JSlider(1, 4, 1); // radius used by median/Gaussian

    public MainFrame() {
        super("Old Photo & Document Restoration Tool - Digital Image Processing Project");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1300, 780);
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());

        imagesPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        imagesPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        originalScroll = wrapInScroll(originalLabel, "Original");
        previewScroll = wrapInScroll(previewLabel, "Preview (After)");
        imagesPanel.add(originalScroll);
        imagesPanel.add(previewScroll);

        add(buildToolbar(), BorderLayout.NORTH);
        add(imagesPanel, BorderLayout.CENTER);
        add(new JScrollPane(buildControlsPanel()), BorderLayout.EAST);
        add(statusLabel, BorderLayout.SOUTH);
        statusLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
    }

    private JScrollPane wrapInScroll(JLabel label, String title) {
        label.setVerticalAlignment(SwingConstants.CENTER);
        JScrollPane scroll = new JScrollPane(label);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.GRAY), title,
                TitledBorder.LEFT, TitledBorder.TOP));
        return scroll;
    }

    // =================================================================
    //  MENU BAR (Open / Save / Browse Saved Location / Exit)
    // =================================================================
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem openItem = new JMenuItem("Open Image...");
        openItem.addActionListener(e -> openImage());

        JMenuItem saveItem = new JMenuItem("Save Result As...");
        saveItem.addActionListener(e -> saveImage());

        browseItem = new JMenuItem("Browse Saved Location");
        browseItem.setEnabled(false); // enabled once something has been saved
        browseItem.addActionListener(e -> browseSavedLocation());

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(browseItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);
        return menuBar;
    }

    // =================================================================
    //  TOOLBAR (Zoom + Hide Original) - sits above the image panels
    // =================================================================
    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton zoomInBtn = new JButton("Zoom In (+)");
        zoomInBtn.addActionListener(e -> { zoomFactor = Math.min(6.0, zoomFactor * 1.25); refreshDisplay(); });

        JButton zoomOutBtn = new JButton("Zoom Out (-)");
        zoomOutBtn.addActionListener(e -> { zoomFactor = Math.max(0.1, zoomFactor / 1.25); refreshDisplay(); });

        JButton zoomResetBtn = new JButton("Reset Zoom");
        zoomResetBtn.addActionListener(e -> { zoomFactor = 1.0; refreshDisplay(); });

        hideOriginalButton = new JButton("Hide Original");
        hideOriginalButton.addActionListener(e -> toggleOriginalVisibility());

        bar.add(zoomInBtn);
        bar.add(zoomOutBtn);
        bar.add(zoomResetBtn);
        bar.add(zoomLabel);
        bar.addSeparator(new Dimension(20, 0));
        bar.add(hideOriginalButton);
        return bar;
    }

    private void toggleOriginalVisibility() {
        originalHidden = !originalHidden;
        hideOriginalButton.setText(originalHidden ? "Show Original" : "Hide Original");
        imagesPanel.removeAll();
        if (originalHidden) {
            imagesPanel.setLayout(new GridLayout(1, 1));
            imagesPanel.add(previewScroll);
        } else {
            imagesPanel.setLayout(new GridLayout(1, 2, 8, 0));
            imagesPanel.add(originalScroll);
            imagesPanel.add(previewScroll);
        }
        imagesPanel.revalidate();
        imagesPanel.repaint();
    }

    // =================================================================
    //  CONTROLS PANEL (right side): grouped filter buttons + sliders
    // =================================================================
    private JPanel buildControlsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(310, 0));

        // ---- Auto Restore (its own top-level group) ----
        panel.add(groupLabel("Quick Restore"));
        panel.add(actionButton("Auto Restore (One Click)", e -> applyCommittedFilter(ImageFilters::autoRestore)));
        panel.add(Box.createVerticalStrut(10));

        // ---- Group 1: Enhance Image ----
        panel.add(titledGroup("Enhance Image", buildEnhanceGroupContent()));
        panel.add(Box.createVerticalStrut(10));

        // ---- Group 2: Document ----
        panel.add(titledGroup("Document", buildDocumentGroupContent()));
        panel.add(Box.createVerticalStrut(10));

        // ---- History ----
        panel.add(groupLabel("History"));
        panel.add(actionButton("Undo Last Change", e -> undo()));
        panel.add(actionButton("Reset to Original", e -> resetToOriginal()));

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /** Wraps a content panel in a titled border, forming a visible button group. */
    private JPanel titledGroup(String title, JPanel content) {
        content.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(120, 120, 120)), title,
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 13)));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        return content;
    }

    private JPanel buildEnhanceGroupContent() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        p.add(actionButton("Median Filter (Denoise)", e -> applyCommittedFilter(
                img -> ImageFilters.medianFilter(img, 2 * kernelSlider.getValue() + 1))));
        p.add(actionButton("Gaussian Blur", e -> applyCommittedFilter(
                img -> ImageFilters.gaussianBlur(img, kernelSlider.getValue(), 1.0))));
        p.add(actionButton("Histogram Equalization", e -> applyCommittedFilter(ImageFilters::histogramEqualize)));
        p.add(actionButton("Adaptive Equalization (CLAHE)", e -> applyCommittedFilter(
                img -> ImageFilters.claheApprox(img, 32))));
        p.add(actionButton("Sharpen (Unsharp Mask)", e -> applyCommittedFilter(
                img -> ImageFilters.unsharpMask(img, 0.6, 2, 1.0))));

        p.add(smallLabel("Filter Strength (kernel radius)"));
        kernelSlider.setMajorTickSpacing(1);
        kernelSlider.setPaintTicks(true);
        kernelSlider.setPaintLabels(true);
        kernelSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(kernelSlider);

        p.add(Box.createVerticalStrut(10));

        // ---- Brightness / Contrast: LIVE sliders, no Apply button ----
        p.add(smallLabel("Brightness / Contrast (live)"));
        p.add(new JLabel("Brightness"));
        brightnessSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(brightnessSlider);
        p.add(new JLabel("Contrast (%)"));
        contrastSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(contrastSlider);
        p.add(actionButton("Reset Brightness/Contrast", e -> resetBrightnessContrast()));

        installLiveSlider(brightnessSlider, this::liveBrightnessContrast, this::commitBrightnessContrast);
        installLiveSlider(contrastSlider, this::liveBrightnessContrast, this::commitBrightnessContrast);

        p.add(Box.createVerticalStrut(10));

        // ---- Color: LIVE sliders (hue / saturation) ----
        p.add(smallLabel("Color Adjustment (live)"));
        p.add(new JLabel("Hue Shift"));
        hueSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hueSlider);
        p.add(new JLabel("Saturation (%)"));
        saturationSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(saturationSlider);
        p.add(actionButton("Reset Color", e -> resetColor()));

        installLiveSlider(hueSlider, this::liveColor, this::commitColor);
        installLiveSlider(saturationSlider, this::liveColor, this::commitColor);

        return p;
    }

    private JPanel buildDocumentGroupContent() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(actionButton("Grayscale", e -> applyCommittedFilter(ImageFilters::grayscale)));
        p.add(actionButton("Document Mode (Otsu Binarize)", e -> applyCommittedFilter(ImageFilters::otsuBinarize)));
        return p;
    }

    private JLabel groupLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(4, 0, 4, 0));
        return label;
    }

    private JLabel smallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(6, 0, 2, 0));
        return label;
    }

    private JButton actionButton(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        button.addActionListener(listener);
        return button;
    }

    // =================================================================
    //  Generic "click a button, apply a filter once, commit it" path
    //  Used by every non-slider filter button (median, blur, histogram
    //  equalization, CLAHE, sharpen, grayscale, Otsu, Auto Restore).
    // =================================================================
    @FunctionalInterface
    private interface Filter {
        BufferedImage apply(BufferedImage input);
    }

    private void applyCommittedFilter(Filter filter) {
        if (currentImage == null) {
            JOptionPane.showMessageDialog(this, "Please open an image first.");
            return;
        }
        undoStack.push(currentImage);
        long start = System.currentTimeMillis();
        currentImage = filter.apply(currentImage);
        long elapsed = System.currentTimeMillis() - start;
        refreshDisplay();
        statusLabel.setText("Filter applied in " + elapsed + " ms. Image size: "
                + currentImage.getWidth() + " x " + currentImage.getHeight());
    }

    // =================================================================
    //  LIVE slider mechanics (Brightness/Contrast and Color)
    //  - installLiveSlider wires both a live-preview ChangeListener and
    //    a MouseListener that commits the change on release (or on a
    //    keyboard-driven change, since arrow keys don't fire mouse events).
    // =================================================================
    private void installLiveSlider(JSlider slider, Runnable onLiveChange, Runnable onCommit) {
        slider.addChangeListener((ChangeEvent e) -> onLiveChange.run());
        slider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                onCommit.run();
            }
        });
    }

    private void liveBrightnessContrast() {
        if (currentImage == null) return;
        if (bcBase == null) bcBase = currentImage; // capture base on first touch
        double alpha = contrastSlider.getValue() / 100.0;
        int beta = brightnessSlider.getValue();
        bcPreview = ImageFilters.adjustBrightnessContrast(bcBase, alpha, beta);
        showPreview(bcPreview);
    }

    private void commitBrightnessContrast() {
        if (bcBase == null || bcPreview == null) return;
        undoStack.push(bcBase);
        currentImage = bcPreview;
        bcBase = null;
        bcPreview = null;
        statusLabel.setText("Brightness/Contrast committed.");
    }

    private void resetBrightnessContrast() {
        brightnessSlider.setValue(0);
        contrastSlider.setValue(100);
        bcBase = null; // cancel any in-progress (uncommitted) adjustment
        bcPreview = null;
        refreshDisplay(); // fall back to the last committed currentImage
        statusLabel.setText("Brightness/Contrast reset.");
    }

    private void liveColor() {
        if (currentImage == null) return;
        if (colorBase == null) colorBase = currentImage;
        float hue = hueSlider.getValue();
        float sat = saturationSlider.getValue() / 100f;
        colorPreview = ImageFilters.adjustColor(colorBase, hue, sat);
        showPreview(colorPreview);
    }

    private void commitColor() {
        if (colorBase == null || colorPreview == null) return;
        undoStack.push(colorBase);
        currentImage = colorPreview;
        colorBase = null;
        colorPreview = null;
        statusLabel.setText("Color adjustment committed.");
    }

    private void resetColor() {
        hueSlider.setValue(0);
        saturationSlider.setValue(100);
        colorBase = null;
        colorPreview = null;
        refreshDisplay();
        statusLabel.setText("Color adjustment reset.");
    }

    // =================================================================
    //  Undo / Reset (global history)
    // =================================================================
    private void undo() {
        if (undoStack.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to undo.");
            return;
        }
        currentImage = undoStack.pop();
        refreshDisplay();
        statusLabel.setText("Undo applied.");
    }

    private void resetToOriginal() {
        if (originalImage == null) return;
        undoStack.clear();
        bcBase = null; bcPreview = null;
        colorBase = null; colorPreview = null;
        brightnessSlider.setValue(0);
        contrastSlider.setValue(100);
        hueSlider.setValue(0);
        saturationSlider.setValue(100);
        currentImage = deepCopy(originalImage);
        refreshDisplay();
        statusLabel.setText("Reset to original image.");
    }

    private BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics g = copy.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    // =================================================================
    //  DISPLAY / ZOOM
    // =================================================================
    private void showPreview(BufferedImage img) {
        previewLabel.setIcon(scaledIcon(img));
        previewLabel.setText(null);
    }

    private void refreshDisplay() {
        if (originalImage != null) {
            originalLabel.setIcon(scaledIcon(originalImage));
            originalLabel.setText(null);
        }
        if (currentImage != null) {
            previewLabel.setIcon(scaledIcon(currentImage));
            previewLabel.setText(null);
        }
        zoomLabel.setText(Math.round(zoomFactor * 100) + "%");
    }

    private ImageIcon scaledIcon(BufferedImage img) {
        int w = Math.max(1, (int) Math.round(img.getWidth() * zoomFactor));
        int h = Math.max(1, (int) Math.round(img.getHeight() * zoomFactor));
        Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    // =================================================================
    //  FILE I/O
    // =================================================================
    private void openImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Image files", "jpg", "jpeg", "png", "bmp"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        try {
            BufferedImage loaded = ImageIO.read(chooser.getSelectedFile());
            if (loaded == null) {
                JOptionPane.showMessageDialog(this, "Could not read that file as an image.");
                return;
            }
            originalImage = deepCopy(loaded);
            currentImage = deepCopy(loaded);
            undoStack.clear();
            bcBase = null; bcPreview = null;
            colorBase = null; colorPreview = null;
            brightnessSlider.setValue(0);
            contrastSlider.setValue(100);
            hueSlider.setValue(0);
            saturationSlider.setValue(100);
            zoomFactor = 1.0;

            refreshDisplay();
            statusLabel.setText("Loaded: " + chooser.getSelectedFile().getName()
                    + " (" + originalImage.getWidth() + " x " + originalImage.getHeight() + ")");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error loading image: " + ex.getMessage());
        }
    }

    private void saveImage() {
        if (currentImage == null) {
            JOptionPane.showMessageDialog(this, "Nothing to save yet.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("restored.png"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        try {
            File file = chooser.getSelectedFile();
            String name = file.getName().toLowerCase();
            String format = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : "png";
            ImageIO.write(currentImage, format, file);
            lastSavedFile = file;
            browseItem.setEnabled(true);
            statusLabel.setText("Saved to: " + file.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error saving image: " + ex.getMessage());
        }
    }

    /** Opens the system file browser at the folder containing the last saved image. */
    private void browseSavedLocation() {
        if (lastSavedFile == null) {
            JOptionPane.showMessageDialog(this, "Save an image first.");
            return;
        }
        File folder = lastSavedFile.getParentFile();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Saved file location:\n" + lastSavedFile.getAbsolutePath());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not open file browser. Saved file location:\n" + lastSavedFile.getAbsolutePath());
        }
    }
}
