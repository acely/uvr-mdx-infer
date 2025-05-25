package com.example.audioseparator;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class TaskPanel extends JPanel {

    private File audioFile;
    private JLabel fileNameLabel;
    JProgressBar progressBar; // Made package-private for direct access from SeparationAppGui listener
    private JButton removeButton;

    public TaskPanel(File audioFile) {
        if (audioFile == null) {
            throw new IllegalArgumentException("audioFile cannot be null");
        }
        this.audioFile = audioFile;

        // Initialize components
        fileNameLabel = new JLabel(audioFile.getName());
        fileNameLabel.setPreferredSize(new Dimension(250, 30)); // Give it some space
        fileNameLabel.setToolTipText(audioFile.getAbsolutePath());


        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(200, 30));


        removeButton = new JButton("Remove");

        // Layout
        setLayout(new FlowLayout(FlowLayout.LEFT, 10, 10)); // Add some hgap and vgap
        // Consider a more structured layout if FlowLayout isn't enough, e.g. BorderLayout or GridBagLayout
        // For BorderLayout:
        // setLayout(new BorderLayout(10, 0)); // hgap, vgap
        // JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        // infoPanel.add(fileNameLabel);
        // infoPanel.add(progressBar);
        // add(infoPanel, BorderLayout.CENTER);
        // add(removeButton, BorderLayout.EAST);


        // Simpler FlowLayout for now:
        add(fileNameLabel);
        add(progressBar);
        add(removeButton);
        

        setPreferredSize(new Dimension(600, 80));
        setBorder(BorderFactory.createEtchedBorder()); // Add a border for visual separation
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
            // Consider if progress should be set to 0 or 100 or left as is on error
            // progressBar.setValue(100); // Or 0, or keep current
            progressBar.setString("Error!");
        } else {
            // Revert to default color (or whatever was set before)
            progressBar.setForeground(UIManager.getColor("ProgressBar.foreground")); 
        }
    }

    // Optional: Override getPreferredSize if components might make it larger than 600x80
    // and you want to strictly enforce it, though FlowLayout might wrap with fixed size.
}
