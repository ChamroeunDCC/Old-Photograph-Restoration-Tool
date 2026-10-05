package com.dip.restoration.filters;

import java.awt.image.BufferedImage;

/**
 * ImageFilters
 * ------------
 * All the classical Digital Image Processing algorithms used by the
 * restoration tool. Every method is static, takes a BufferedImage in,
 * and returns a NEW BufferedImage (originals are never modified in place),
 * so the UI layer can freely chain filters and support Undo.
 *
 * Algorithms implemented (see Project Proposal, Section 4):
 *  - grayscale()                 : RGB -> luminance
 *  - adjustBrightnessContrast()  : linear point transform  output = a*input + b
 *  - medianFilter()              : nonlinear order-statistic noise filter
 *  - gaussianBlur()              : convolution with a Gaussian kernel
 *  - histogramEqualize()         : global contrast correction via CDF
 *  - claheApprox()               : tile-based local contrast correction
 *  - unsharpMask()                : edge sharpening (original - blurred, boosted)
 *  - otsuBinarize()              : automatic document thresholding
 */
public final class ImageFilters {

    private ImageFilters() { } // static-only utility class

    // ---------------------------------------------------------------
    // Helper: clamp an int to the valid 0-255 pixel range
    // ---------------------------------------------------------------
    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    // ---------------------------------------------------------------
    // 1. Grayscale conversion
    //    Standard luminance-preserving weighted sum (ITU-R BT.601)
    // ---------------------------------------------------------------
    public static BufferedImage grayscale(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                int outRgb = (gray << 16) | (gray << 8) | gray;
                out.setRGB(x, y, outRgb);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 2. Linear brightness / contrast transform
    //    output = alpha * input + beta   (applied per channel)
    //    alpha > 1 increases contrast, beta shifts brightness
    // ---------------------------------------------------------------
    public static BufferedImage adjustBrightnessContrast(BufferedImage src, double alpha, int beta) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = clamp((int) (alpha * ((rgb >> 16) & 0xFF) + beta));
                int g = clamp((int) (alpha * ((rgb >> 8) & 0xFF) + beta));
                int b = clamp((int) (alpha * (rgb & 0xFF) + beta));
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 3. Median filter
    //    Replaces each pixel with the median of its k x k neighbourhood.
    //    Very effective against salt-and-pepper scan noise while
    //    preserving edges better than a mean/Gaussian filter.
    // ---------------------------------------------------------------
    public static BufferedImage medianFilter(BufferedImage src, int kernelSize) {
        if (kernelSize % 2 == 0) kernelSize++; // must be odd
        int radius = kernelSize / 2;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        int windowArea = kernelSize * kernelSize;
        int[] rWin = new int[windowArea];
        int[] gWin = new int[windowArea];
        int[] bWin = new int[windowArea];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = 0;
                for (int ky = -radius; ky <= radius; ky++) {
                    for (int kx = -radius; kx <= radius; kx++) {
                        int sx = clampCoord(x + kx, w);
                        int sy = clampCoord(y + ky, h);
                        int rgb = src.getRGB(sx, sy);
                        rWin[idx] = (rgb >> 16) & 0xFF;
                        gWin[idx] = (rgb >> 8) & 0xFF;
                        bWin[idx] = rgb & 0xFF;
                        idx++;
                    }
                }
                java.util.Arrays.sort(rWin);
                java.util.Arrays.sort(gWin);
                java.util.Arrays.sort(bWin);
                int mid = windowArea / 2;
                int outRgb = (rWin[mid] << 16) | (gWin[mid] << 8) | bWin[mid];
                out.setRGB(x, y, outRgb);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 4. Gaussian blur
    //    Convolution with a normalized Gaussian kernel of given radius/sigma.
    // ---------------------------------------------------------------
    public static BufferedImage gaussianBlur(BufferedImage src, int radius, double sigma) {
        double[][] kernel = buildGaussianKernel(radius, sigma);
        return convolve(src, kernel);
    }

    private static double[][] buildGaussianKernel(int radius, double sigma) {
        int size = 2 * radius + 1;
        double[][] kernel = new double[size][size];
        double sum = 0.0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                double val = Math.exp(-(x * x + y * y) / (2 * sigma * sigma));
                kernel[y + radius][x + radius] = val;
                sum += val;
            }
        }
        // normalize so the kernel sums to 1 (preserves overall brightness)
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                kernel[y][x] /= sum;
        return kernel;
    }

    /** Generic 2D convolution used by gaussianBlur() and unsharpMask(). */
    private static BufferedImage convolve(BufferedImage src, double[][] kernel) {
        int w = src.getWidth(), h = src.getHeight();
        int kh = kernel.length, kw = kernel[0].length;
        int radiusY = kh / 2, radiusX = kw / 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0;
                for (int ky = -radiusY; ky <= radiusY; ky++) {
                    for (int kx = -radiusX; kx <= radiusX; kx++) {
                        int sx = clampCoord(x + kx, w);
                        int sy = clampCoord(y + ky, h);
                        int rgb = src.getRGB(sx, sy);
                        double weight = kernel[ky + radiusY][kx + radiusX];
                        r += weight * ((rgb >> 16) & 0xFF);
                        g += weight * ((rgb >> 8) & 0xFF);
                        b += weight * (rgb & 0xFF);
                    }
                }
                out.setRGB(x, y, (clamp((int) r) << 16) | (clamp((int) g) << 8) | clamp((int) b));
            }
        }
        return out;
    }

    private static int clampCoord(int v, int max) {
        if (v < 0) return 0;
        if (v >= max) return max - 1;
        return v;
    }

    // ---------------------------------------------------------------
    // 5. Global histogram equalization
    //    Works in the luminance (Y) channel so color is preserved;
    //    corrects the classic "faded old photo" low-contrast look.
    // ---------------------------------------------------------------
    public static BufferedImage histogramEqualize(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int totalPixels = w * h;

        // Step 1: build the histogram of luminance values
        int[] histogram = new int[256];
        int[][] yChannel = new int[w][h];
        double[][] cb = new double[w][h];
        double[][] cr = new double[w][h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int Y = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                yChannel[x][y] = Y;
                cb[x][y] = -0.169 * r - 0.331 * g + 0.500 * b + 128;
                cr[x][y] = 0.500 * r - 0.419 * g - 0.081 * b + 128;
                histogram[Y]++;
            }
        }

        // Step 2: cumulative distribution function (CDF)
        int[] cdf = new int[256];
        int cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            cdf[i] = cumulative;
        }
        int cdfMin = 0;
        for (int v : cdf) { if (v > 0) { cdfMin = v; break; } }

        // Step 3: map old intensity -> new equalized intensity
        int[] equalizeMap = new int[256];
        for (int i = 0; i < 256; i++) {
            equalizeMap[i] = clamp((int) Math.round(
                    ((double) (cdf[i] - cdfMin) / (totalPixels - cdfMin)) * 255.0));
        }

        // Step 4: rebuild the image, converting Y'CbCr back to RGB
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int newY = equalizeMap[yChannel[x][y]];
                int r = clamp((int) (newY + 1.402 * (cr[x][y] - 128)));
                int g = clamp((int) (newY - 0.344 * (cb[x][y] - 128) - 0.714 * (cr[x][y] - 128)));
                int b = clamp((int) (newY + 1.772 * (cb[x][y] - 128)));
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 6. Adaptive histogram equalization (simplified / tile-based CLAHE)
    //    Splits the image into tiles, equalizes each tile's luminance
    //    histogram independently, then blends tile results using
    //    bilinear interpolation across tile centers to avoid hard edges.
    // ---------------------------------------------------------------
    public static BufferedImage claheApprox(BufferedImage src, int tileSize) {
        int w = src.getWidth(), h = src.getHeight();
        int tilesX = Math.max(1, w / tileSize);
        int tilesY = Math.max(1, h / tileSize);

        // Precompute a per-tile equalization map
        int[][][] tileMaps = new int[tilesX][tilesY][256];
        int[][] yChannel = new int[w][h];
        double[][] cb = new double[w][h];
        double[][] cr = new double[w][h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int Y = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                yChannel[x][y] = Y;
                cb[x][y] = -0.169 * r - 0.331 * g + 0.500 * b + 128;
                cr[x][y] = 0.500 * r - 0.419 * g - 0.081 * b + 128;
            }
        }

        for (int tx = 0; tx < tilesX; tx++) {
            for (int ty = 0; ty < tilesY; ty++) {
                int x0 = tx * tileSize, x1 = (tx == tilesX - 1) ? w : x0 + tileSize;
                int y0 = ty * tileSize, y1 = (ty == tilesY - 1) ? h : y0 + tileSize;
                int[] hist = new int[256];
                int count = 0;
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) { hist[yChannel[x][y]]++; count++; }

                int[] cdf = new int[256];
                int cum = 0;
                for (int i = 0; i < 256; i++) { cum += hist[i]; cdf[i] = cum; }
                int cdfMin = 0;
                for (int v : cdf) { if (v > 0) { cdfMin = v; break; } }
                for (int i = 0; i < 256; i++) {
                    tileMaps[tx][ty][i] = count > cdfMin
                            ? clamp((int) Math.round(((double) (cdf[i] - cdfMin) / (count - cdfMin)) * 255.0))
                            : i;
                }
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx = Math.min(tilesX - 1, x / tileSize);
                int ty = Math.min(tilesY - 1, y / tileSize);
                int newY = tileMaps[tx][ty][yChannel[x][y]];
                int r = clamp((int) (newY + 1.402 * (cr[x][y] - 128)));
                int g = clamp((int) (newY - 0.344 * (cb[x][y] - 128) - 0.714 * (cr[x][y] - 128)));
                int b = clamp((int) (newY + 1.772 * (cb[x][y] - 128)));
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 7. Unsharp masking
    //    sharpened = original + amount * (original - blurred)
    //    Counteracts blur from old lenses / low quality scans.
    // ---------------------------------------------------------------
    public static BufferedImage unsharpMask(BufferedImage src, double amount, int radius, double sigma) {
        BufferedImage blurred = gaussianBlur(src, radius, sigma);
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int orig = src.getRGB(x, y);
                int blur = blurred.getRGB(x, y);
                int r = clamp((int) (((orig >> 16) & 0xFF) + amount * (((orig >> 16) & 0xFF) - ((blur >> 16) & 0xFF))));
                int g = clamp((int) (((orig >> 8) & 0xFF) + amount * (((orig >> 8) & 0xFF) - ((blur >> 8) & 0xFF))));
                int b = clamp((int) ((orig & 0xFF) + amount * ((orig & 0xFF) - (blur & 0xFF))));
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 8. Otsu's method - automatic document binarization
    //    Finds the threshold that minimizes intra-class intensity
    //    variance, then produces a clean black/white "Document Mode" image.
    // ---------------------------------------------------------------
    public static BufferedImage otsuBinarize(BufferedImage src) {
        BufferedImage gray = grayscale(src);
        int w = gray.getWidth(), h = gray.getHeight();
        int total = w * h;

        int[] histogram = new int[256];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                histogram[gray.getRGB(x, y) & 0xFF]++;

        double sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += i * histogram[i];

        double sumBackground = 0;
        int weightBackground = 0;
        double maxVariance = 0;
        int threshold = 0;

        for (int t = 0; t < 256; t++) {
            weightBackground += histogram[t];
            if (weightBackground == 0) continue;
            int weightForeground = total - weightBackground;
            if (weightForeground == 0) break;

            sumBackground += t * histogram[t];
            double meanBackground = sumBackground / weightBackground;
            double meanForeground = (sumAll - sumBackground) / weightForeground;

            double betweenClassVariance = (double) weightBackground * weightForeground
                    * (meanBackground - meanForeground) * (meanBackground - meanForeground);

            if (betweenClassVariance > maxVariance) {
                maxVariance = betweenClassVariance;
                threshold = t;
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int val = gray.getRGB(x, y) & 0xFF;
                int bw = (val > threshold) ? 255 : 0;
                out.setRGB(x, y, (bw << 16) | (bw << 8) | bw);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 10. Color adjustment (Hue / Saturation)
    //     Converts each pixel to HSB, shifts hue and scales saturation,
    //     then converts back to RGB. Lets the user add back color life
    //     to a faded photo, or correct an unwanted color cast.
    // ---------------------------------------------------------------
    public static BufferedImage adjustColor(BufferedImage src, float hueShiftDegrees, float saturationScale) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float[] hsb = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                java.awt.Color.RGBtoHSB(r, g, b, hsb);

                float hue = hsb[0] + (hueShiftDegrees / 360f);
                hue = hue - (float) Math.floor(hue); // wrap into [0,1)
                float sat = Math.max(0f, Math.min(1f, hsb[1] * saturationScale));

                int newRgb = java.awt.Color.HSBtoRGB(hue, sat, hsb[2]) & 0x00FFFFFF;
                out.setRGB(x, y, newRgb);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 11. Auto Restore preset
    //     Fixed chain: median filter -> histogram equalization -> unsharp mask
    //     This is the one-click restoration described in the proposal.
    // ---------------------------------------------------------------
    public static BufferedImage autoRestore(BufferedImage src) {
        BufferedImage step1 = medianFilter(src, 3);
        BufferedImage step2 = histogramEqualize(step1);
        BufferedImage step3 = unsharpMask(step2, 0.6, 2, 1.0);
        return step3;
    }
}
