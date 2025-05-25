package com.example.audioseparator;

//Keep existing SLF4J imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//Standard Java Swing and AWT imports
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

//Imports for Drag and Drop functionality
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.Point; // For drop location
import java.awt.Rectangle; // For component bounds
import java.awt.Component; // For getComponent and instanceof checks
import java.util.function.Supplier; // For index supplier
import java.util.concurrent.atomic.AtomicReference; // For index supplier

//Other necessary imports
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TaskPanel is in the same package, so direct import like below is optional
// but can be good for clarity when accessing static members like TASK_PANEL_FLAVOR.
// import com.example.audioseparator.TaskPanel; 

public class SeparationAppGui extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(SeparationAppGui.class);

    private JPanel tasksContainerPanel;
    private JButton addFilesButton;
    private JButton startProcessingButton;
    private JFileChooser fileChooser;
    private JTextField modelPathField; // For displaying/editing model path

    private List<TaskPanel> taskPanelsList = new ArrayList<>();
    private AudioSeparationService audioService;
    private String currentModelPath; // To store the model path

    public SeparationAppGui() {
        setTitle("Audio Separation Tool GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(700, 500); // Initial size, can be adjusted
        setLocationRelativeTo(null); // Center on screen

        // Initialize components
        tasksContainerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10)); // Center align, add gaps
        JScrollPane scrollPane = new JScrollPane(tasksContainerPanel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); // Only vertical scroll
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        addFilesButton = new JButton("Add Audio File(s)");
        startProcessingButton = new JButton("Start Processing");

        fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                if (f.isDirectory()) {
                    return true;
                }
                String name = f.getName().toLowerCase();
                return name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".flac"); // Example filters
            }

            @Override
            public String getDescription() {
                return "Audio Files (*.wav, *.mp3, *.flac)";
            }
        });

        // Layout for main frame
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("ONNX Model Path:"));
        modelPathField = new JTextField(30); // Adjust size as needed
        modelPathField.setToolTipText("Enter path to .onnx model or leave blank to be prompted.");
        topPanel.add(modelPathField);
        JButton browseModelButton = new JButton("Browse...");
        topPanel.add(browseModelButton);


        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(addFilesButton);
        buttonPanel.add(startProcessingButton);

        setLayout(new BorderLayout(0, 5)); // Add vertical gap between components
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // Add ActionListeners
        browseModelButton.addActionListener(e -> browseForModel());

        addFilesButton.addActionListener(e -> addFiles());
        startProcessingButton.addActionListener(e -> startProcessing());

        // Set up drag and drop
        tasksContainerPanel.setTransferHandler(new FileDropHandler());

        // Window closing listener
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (audioService != null) {
                    try {
                        audioService.close();
                        logger.info("AudioSeparationService closed on GUI exit.");
                    } catch (Exception ex) {
                        logger.error("Error closing AudioSeparationService on GUI exit", ex);
                    }
                }
            }
        });
    }

    // Inner class for handling file drops
    private class FileDropHandler extends TransferHandler {
            @Override
            public boolean canImport(TransferHandler.TransferSupport support) {
                if (support == null) { // Basic check for support itself
                    return false;
                }

                // First, check for TaskPanel index flavor
                if (support.isDataFlavorSupported(TaskPanel.TASK_PANEL_INDEX_FLAVOR)) {
                    return true;
                }

                // Now, handle javaFileListFlavor with robust checks
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    Transferable transferable = support.getTransferable();
                    if (transferable == null) {
                        logger.warn("Transferable is null during canImport for javaFileListFlavor.");
                        return false;
                    }

                    // It's important to also check if the transferable *actually* supports the flavor
                    // before trying to get data from it, to avoid potential exceptions with some L&F or OS.
                    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        // This case implies that isDataFlavorSupported on 'support' was true,
                        // but on 'transferable' it's false. This can happen.
                        return false;
                    }

                    try {
                        java.util.List<File> files = (java.util.List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);

                        if (files == null || files.isEmpty()) {
                            // Log if files is null, as it's somewhat unexpected if flavor was supported
                            if (files == null) {
                                logger.warn("getTransferData for javaFileListFlavor returned null.");
                            }
                            return false;
                        }

                        for (File file : files) {
                            if (file == null) { // Check for null files in the list
                                logger.warn("Null file found in file list during canImport.");
                                continue; // Skip this null file
                            }
                            if (file.isDirectory()) {
                                continue; // We are interested in files, not directories
                            }
                            String name = file.getName().toLowerCase();
                            if (name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".flac")) {
                                return true; // Found at least one valid audio file
                            }
                        }
                        // No valid audio files found in the list
                        return false;
                    } catch (UnsupportedFlavorException | IOException e) {
                        logger.warn("Exception during canImport file check: {}", e.getMessage(), e);
                        return false;
                    }
                }
                // Neither TaskPanel nor a valid FileList flavor is supported
                return false;
            }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferHandler.TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Transferable transferable = support.getTransferable();
            try {
                if (support.isDataFlavorSupported(TaskPanel.TASK_PANEL_INDEX_FLAVOR)) {
                    int draggedPanelOldIndex = (Integer) transferable.getTransferData(TaskPanel.TASK_PANEL_INDEX_FLAVOR);
                    
                    TaskPanel draggedPanel;
                    try {
                        draggedPanel = taskPanelsList.get(draggedPanelOldIndex);
                    } catch (IndexOutOfBoundsException e) {
                        logger.error("Invalid index {} received for dragged TaskPanel. List size: {}.", draggedPanelOldIndex, taskPanelsList.size(), e);
                        return false;
                    }
                    
                    // Remove the panel using its old index before calculating new position
                    taskPanelsList.remove(draggedPanelOldIndex);
                    // tasksContainerPanel.remove(draggedPanel); // This will be handled by removeAll and re-add

                    Point dropPoint = support.getDropLocation().getDropPoint();
                    int newLogicalIndex = 0;
                    for (int i = 0; i < tasksContainerPanel.getComponentCount(); i++) {
                        Component comp = tasksContainerPanel.getComponent(i);
                        if (comp == draggedPanel) continue; 

                        Rectangle bounds = comp.getBounds();
                        if (dropPoint.y >= bounds.y && dropPoint.y <= bounds.y + bounds.height) { 
                            if (dropPoint.x < bounds.x + bounds.width / 2) { 
                                break; 
                            } else {
                                newLogicalIndex++; 
                            }
                        } else if (dropPoint.y < bounds.y) { 
                            break; 
                        } else { 
                            newLogicalIndex++; 
                        }
                    }
                    newLogicalIndex = Math.max(0, Math.min(newLogicalIndex, taskPanelsList.size()));

                    taskPanelsList.add(newLogicalIndex, draggedPanel);

                    tasksContainerPanel.removeAll();
                    for (TaskPanel tp : taskPanelsList) {
                        tasksContainerPanel.add(tp);
                    }

                    tasksContainerPanel.revalidate();
                    tasksContainerPanel.repaint();
                    logger.info("TaskPanel reordered. New list size: {}. Reordered Panel: {}", taskPanelsList.size(), draggedPanel.getFilePath());
                    return true;

                } else if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    java.util.List<File> files = (java.util.List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    int filesAddedCount = 0;
                    for (File file : files) {
                        String name = file.getName().toLowerCase();
                        if (file.isFile() && (name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".flac"))) {
                            AtomicReference<TaskPanel> panelRef = new AtomicReference<>();
                            Supplier<Integer> indexSupplier = () -> taskPanelsList.indexOf(panelRef.get());

                            TaskPanel taskPanel = new TaskPanel(file, indexSupplier);
                            panelRef.set(taskPanel); // Set ref after TaskPanel created
                            
                            final String filePath = file.getAbsolutePath(); 
                            taskPanel.getRemoveButton().addActionListener(evt -> {
                                tasksContainerPanel.remove(taskPanel); 
                                taskPanelsList.remove(taskPanel);
                                tasksContainerPanel.revalidate();
                                tasksContainerPanel.repaint();
                                logger.info("TaskPanel removed: {}. Remaining tasks: {}", filePath, taskPanelsList.size());
                            });
                            tasksContainerPanel.add(taskPanel);
                            taskPanelsList.add(taskPanel); // Add to list
                            filesAddedCount++;
                        }
                    }
                    if (filesAddedCount > 0) {
                        tasksContainerPanel.revalidate();
                        tasksContainerPanel.repaint();
                        logger.info("{} audio file(s) dropped and added. Total tasks: {}", filesAddedCount, taskPanelsList.size());
                    }
                    return filesAddedCount > 0;
                }
            } catch (UnsupportedFlavorException | IOException e) {
                logger.error("Error during importData in FileDropHandler: {}", e.getMessage(), e);
            }
            return false;
        }
    }

    private void browseForModel() {
        JFileChooser modelFileChooser = new JFileChooser();
        modelFileChooser.setDialogTitle("Select ONNX Model File");
        modelFileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".onnx");
            }
            @Override
            public String getDescription() {
                return "ONNX Model Files (*.onnx)";
            }
        });
        int returnValue = modelFileChooser.showOpenDialog(this);
        if (returnValue == JFileChooser.APPROVE_OPTION) {
            File modelFile = modelFileChooser.getSelectedFile();
            currentModelPath = modelFile.getAbsolutePath();
            modelPathField.setText(currentModelPath);
            logger.info("Model path set to: {}", currentModelPath);
        }
    }

    private void addFiles() {
        int returnValue = fileChooser.showOpenDialog(this);
        if (returnValue == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();
            for (File file : selectedFiles) {
                AtomicReference<TaskPanel> panelRef = new AtomicReference<>();
                Supplier<Integer> indexSupplier = () -> taskPanelsList.indexOf(panelRef.get());
                
                TaskPanel taskPanel = new TaskPanel(file, indexSupplier);
                panelRef.set(taskPanel); // Set the reference after TaskPanel is created

                taskPanel.getRemoveButton().addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent evt) {
                        tasksContainerPanel.remove(taskPanel);
                        taskPanelsList.remove(taskPanel); 
                        tasksContainerPanel.revalidate();
                        tasksContainerPanel.repaint();
                        logger.info("TaskPanel removed: {}. Remaining tasks: {}", taskPanel.getFilePath(), taskPanelsList.size());
                    }
                });
                tasksContainerPanel.add(taskPanel);
                taskPanelsList.add(taskPanel); 
            }
            tasksContainerPanel.revalidate();
            tasksContainerPanel.repaint();
        }
    }

    private void startProcessing() {
        currentModelPath = modelPathField.getText(); // Get current text from field
        if (currentModelPath == null || currentModelPath.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify the ONNX model path.", "Model Path Required", JOptionPane.ERROR_MESSAGE);
            browseForModel(); // Prompt user to select model
            currentModelPath = modelPathField.getText(); // Re-check after prompt
            if (currentModelPath == null || currentModelPath.trim().isEmpty()){
                 logger.warn("Processing cancelled by user (no model path provided).");
                 return;
            }
        }
        
        final File modelFile = new File(currentModelPath);
        if (!modelFile.exists() || !modelFile.isFile()) {
             JOptionPane.showMessageDialog(this, "Model file not found or is invalid: " + currentModelPath, "Model Error", JOptionPane.ERROR_MESSAGE);
             return;
        }


        if (taskPanelsList.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files added to process.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Initialize AudioSeparationService if needed (or if model path changed)
        // For simplicity, let's re-initialize each time processing starts to pick up new model path.
        // A more advanced implementation might cache the service if the path hasn't changed.
        if (audioService != null) {
            try {
                audioService.close(); // Close previous service if any
            } catch (Exception e) {
                logger.error("Error closing previous audio service instance", e);
            }
        }
        
        Map<String, Object> processingParams = new HashMap<>();
        processingParams.put("margin", 44100); // Default 1s margin
        processingParams.put("chunks", 45);
        processingParams.put("n_fft", 6144);
        processingParams.put("dim_t_exponent", 8);
        processingParams.put("dim_f", 2048);
        processingParams.put("denoise", false); // Example: controlled by a GUI checkbox later
        processingParams.put("output_content", "both"); // Example
        // sample_rate is handled by AudioSeparationService default (44100.0f)

        try {
            logger.info("Initializing AudioSeparationService with model: {}", currentModelPath);
            audioService = new AudioSeparationService(currentModelPath, processingParams);
        } catch (Exception e) {
            logger.error("Failed to initialize AudioSeparationService", e);
            JOptionPane.showMessageDialog(this, "Failed to initialize audio separation service: " + e.getMessage(), "Initialization Error", JOptionPane.ERROR_MESSAGE);
            audioService = null; // Ensure it's null if failed
            return;
        }

        addFilesButton.setEnabled(false);
        startProcessingButton.setEnabled(false);
        modelPathField.setEnabled(false); // Disable model path field during processing

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<TaskPanel> tasksToProcess = new ArrayList<>(taskPanelsList); // Process a copy

                for (TaskPanel taskPanel : tasksToProcess) {
                    final TaskPanel currentTaskPanel = taskPanel; // For use in listener
                    SwingUtilities.invokeLater(() -> {
                         currentTaskPanel.setProgress(0);
                         currentTaskPanel.setProgressText("Starting...");
                         currentTaskPanel.setErrorState(false); // Clear previous error state
                    });

                    String inputPath = currentTaskPanel.getFilePath();
                    File inputFile = new File(inputPath);
                    String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
                    String outputDir = inputFile.getParent();
                    if (outputDir == null) outputDir = "."; // Default to current dir if no parent

                    String vocalsOutPath = Paths.get(outputDir, baseName + "_vocals.wav").toString();
                    String accompOutPath = Paths.get(outputDir, baseName + "_accompaniment.wav").toString();

                    ProgressListener taskListener = new ProgressListener() {
                        @Override
                        public void progressChanged(int current, int total) {
                            SwingUtilities.invokeLater(() -> {
                                if (total > 0) {
                                    currentTaskPanel.setProgress((current * 100) / total);
                                }
                            });
                        }
                        @Override
                        public void progressPublish(String info) {
                            SwingUtilities.invokeLater(() -> currentTaskPanel.setProgressText(info));
                        }
                        @Override
                        public void progressDone() {
                            SwingUtilities.invokeLater(() -> {
                                currentTaskPanel.setProgress(100);
                                currentTaskPanel.setProgressText("Done");
                            });
                        }
                        @Override
                        public void progressError() {
                            SwingUtilities.invokeLater(() -> {
                                currentTaskPanel.setErrorState(true); // Sets text to "Error!" and color
                            });
                        }
                    };

                    try {
                        logger.info("Worker: Starting demixFile for {}", inputPath);
                        audioService.demixFile(inputPath, vocalsOutPath, accompOutPath, taskListener);
                        logger.info("Worker: Finished demixFile for {}", inputPath);
                    } catch (Exception e) {
                        logger.error("Worker: Error processing file {}", inputPath, e);
                        // Listener's progressError should have been called by AudioSeparationService
                        // If not, call it explicitly here:
                        // taskListener.progressError(); // Already handled by service if it throws
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                addFilesButton.setEnabled(true);
                startProcessingButton.setEnabled(true);
                modelPathField.setEnabled(true);
                try {
                    get(); // To catch any unhandled exceptions from doInBackground
                    JOptionPane.showMessageDialog(SeparationAppGui.this, "All files processed.", "Processing Complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    logger.error("Error during background processing execution", e);
                    JOptionPane.showMessageDialog(SeparationAppGui.this, "An error occurred during processing: " + e.getMessage(), "Processing Error", JOptionPane.ERROR_MESSAGE);
                }
                logger.info("All processing tasks finished or failed.");
            }
        };
        worker.execute();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new SeparationAppGui().setVisible(true);
            }
        });
    }
}
