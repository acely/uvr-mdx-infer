package com.example.audioseparator;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.Image; // For drag image
import java.awt.image.BufferedImage; // For drag image
import java.awt.Graphics2D; // For drag image
import java.awt.Dimension; // For drag image
// For instanceof checks:
import javax.swing.JButton;
import javax.swing.JProgressBar;
import java.awt.Component;


public class TaskPanel extends JPanel {

    public static final DataFlavor TASK_PANEL_FLAVOR = new DataFlavor(TaskPanel.class, "TaskPanel");

    private File audioFile;
    private JLabel fileNameLabel;
    JProgressBar progressBar; 
    private JButton removeButton;

    public TaskPanel(File audioFile) {
        if (audioFile == null) {
            throw new IllegalArgumentException("audioFile cannot be null");
        }
        this.audioFile = audioFile;

        fileNameLabel = new JLabel(audioFile.getName());
        fileNameLabel.setPreferredSize(new Dimension(250, 30)); 
        fileNameLabel.setToolTipText(audioFile.getAbsolutePath());

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(200, 30));

        removeButton = new JButton("Remove");

        setLayout(new FlowLayout(FlowLayout.LEFT, 10, 10)); 
        add(fileNameLabel);
        add(progressBar);
        add(removeButton);
        
        setPreferredSize(new Dimension(600, 80));
        setBorder(BorderFactory.createEtchedBorder());

        setTransferHandler(new TaskPanelDragHandler());

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) { 
                    return;
                }
                Component clickedComponent = findComponentAt(e.getPoint());
                if (clickedComponent != TaskPanel.this && (clickedComponent instanceof JButton || clickedComponent instanceof JProgressBar)) {
                    return;
                }

                TaskPanel panel = TaskPanel.this;
                TransferHandler handler = panel.getTransferHandler();
                if (handler != null) {
                    handler.exportAsDrag(panel, e, TransferHandler.MOVE);
                }
            }
        });
    }

    // Inner class for Transferable
    private static class TaskPanelTransferable implements Transferable {
        private TaskPanel panel;

        public TaskPanelTransferable(TaskPanel panel) {
            this.panel = panel;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { TASK_PANEL_FLAVOR };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return TASK_PANEL_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return panel;
        }
    }

    // Inner class for Drag Handler
    private static class TaskPanelDragHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return TransferHandler.MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            TaskPanel panel = (TaskPanel) c;
            Dimension size = panel.getSize();
            // Ensure size is valid before creating BufferedImage
            if (size.width <= 0 || size.height <= 0) {
                // Fallback or log error if size is invalid, though typically components should have valid sizes.
                // For now, just create a minimal image or skip drag image if size is problematic.
                // This might happen if the component is not yet fully laid out.
                // However, by the time mousePressed occurs, it should be.
                // Let's assume valid size for now.
            }
            BufferedImage dragImage = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = dragImage.createGraphics();
            panel.paint(g2); // Paint the component onto the image
            g2.dispose();
            setDragImage(dragImage); // Set the drag image
            return new TaskPanelTransferable(panel);
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            super.exportDone(source, data, action);
        }
    }

    public String getFilePath() {
        return audioFile.getAbsolutePath();
    }

    public File getAudioFile() {
        return audioFile;
    }

    public void setProgress(int value) {
        progressBar.setValue(Math.max(0, Math.min(value, 100)));
    }

    public void setProgressText(String text) {
        progressBar.setString(text);
    }

    public JButton getRemoveButton() {
        return removeButton;
    }

    public void setErrorState(boolean error) {
        if (error) {
            progressBar.setForeground(Color.RED);
            progressBar.setString("Error!");
        } else {
            progressBar.setForeground(UIManager.getColor("ProgressBar.foreground")); 
        }
    }
}
