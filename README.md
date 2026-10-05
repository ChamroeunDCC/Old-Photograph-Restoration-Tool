# Old Photo & Document Restoration Tool

A Java Swing desktop application for Digital Image Processing coursework.
Implements classical DIP algorithms (no ML/training required) to restore
faded, noisy, or blurred old photographs and scanned documents.

## Requirements
- Java JDK 11 or newer (tested with JDK 17)
- No external libraries needed — pure java.awt.image + javax.swing

## How to run

### Option A: Command line
```
cd DIPRestorationTool
javac -d out $(find src -name "*.java")
java -cp out com.dip.restoration.Main
```

### Option B: IntelliJ IDEA / Eclipse
1. Open/Import this folder as a Java project.
2. Mark `src/main/java` as the Sources Root.
3. Set `com.dip.restoration.Main` as the run configuration's main class.
4. Run.

## Project structure
```
src/main/java/com/dip/restoration/
  Main.java                 - application entry point
  ui/MainFrame.java         - Swing GUI (open/save, filters, sliders, undo)
  filters/ImageFilters.java - all DIP algorithms (see Section 4 of proposal)
```

## Algorithms implemented (matches Project Proposal Section 4)
- Grayscale conversion
- Linear brightness/contrast adjustment (live slider preview)
- Color adjustment: hue shift + saturation scale (live slider preview)
- Median filter (noise reduction)
- Gaussian blur
- Global histogram equalization
- Adaptive histogram equalization (simplified CLAHE, tile-based)
- Unsharp masking (sharpening)
- Otsu's automatic thresholding (document binarization)
- Auto Restore preset (median -> histogram eq -> unsharp mask chain)

## UI features
- **Grouped, labeled filter buttons**: "Enhance Image" group (denoise, blur,
  histogram/CLAHE equalization, sharpen, brightness/contrast, color) and
  "Document" group (grayscale, Otsu document mode).
- **Live sliders**: Brightness/Contrast and Hue/Saturation preview instantly
  as you drag, and only commit to the undo history when you release the
  slider (or move it with arrow keys). Each slider group has its own
  "Reset" button that cancels an in-progress adjustment without touching
  earlier filters.
- **Global Undo**: steps back through every committed change, one filter
  at a time (median, blur, histogram eq, CLAHE, sharpen, grayscale, Otsu,
  brightness/contrast commit, color commit are each a single undo step).
- **Reset to Original**: always returns to the freshly loaded image.
- **Zoom In / Zoom Out / Reset Zoom**: scales both the Original and Preview
  panels together; current zoom % shown in the toolbar.
- **Hide Original**: toggles the Original panel off so Preview can use the
  full window width; button relabels to "Show Original" to toggle back.
- **Browse Saved Location**: after Save, opens your system file browser at
  the folder containing the saved image (File menu).

## Next steps (per proposal's week-by-week schedule)
- Week 12: test on real old Khmer photo/document scans, tune parameters
- Week 13: UI polish, icons, error handling
- Week 14: write final report with before/after screenshots and
  stddev/sharpness measurements (see ImageFilters test approach)
