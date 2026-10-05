package com.dip.restoration;

import com.dip.restoration.ui.MainFrame;

import javax.swing.*;

/**
 * Application entry point.
 * Launches the Swing UI on the Event Dispatch Thread (EDT), as required
 * by Swing's single-threaded rule.
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to default look and feel if system L&F is unavailable
            }
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
