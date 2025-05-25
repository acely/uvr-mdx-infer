package com.example.audioseparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class MainSv {

    private static final Logger logger = LoggerFactory.getLogger(MainSv.class);

    // Static nested class for ProgressListener implementation
    private static class ConsoleProgressListener implements ProgressListener {
        @Override
        public void progressChanged(int current, int total) {
            System.out.println("Progress: " + current + "/" + total);
        }

        @Override
        public void progressPublish(String info) {
            System.out.println("Info: " + info);
        }

        @Override
        public void progressDone() {
            System.out.println("Processing done.");
        }

        @Override
        public void progressError() {
            System.err.println("Processing error occurred.");
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java -cp <classpath> com.example.audioseparator.MainSv <modelPath> <inputFilePath> <outputVocalsFilePath> [outputAccompanimentFilePath]");
            System.err.println("Example: java -cp target/audio-separator-1.0-SNAPSHOT.jar com.example.audioseparator.MainSv model.onnx input.wav vocals_out.wav accomp_out.wav");
            System.exit(1);
            return;
        }

        String modelPath = args[0];
        String inputFilePath = args[1];
        String outputVocalsFilePath = args[2];
        String outputAccompanimentFilePath = null;

        Map<String, Object> processingParams = new HashMap<>();
        // Default processing parameters (can be overridden or extended if more CLI args are added)
        processingParams.put("margin", 44100); // 1 second at 44.1kHz
        processingParams.put("chunks", 45);     // Process in 45-second chunks
        processingParams.put("n_fft", 6144);
        processingParams.put("dim_t_exponent", 8); // dim_t = 2^8 = 256
        processingParams.put("dim_f", 2048);
        processingParams.put("denoise", false); // Defaulting to false as per CLI behavior in Main.java if -d is not present

        // Determine output_content and accompaniment path
        // For this test harness, let's allow a 4th argument to control if accompaniment is saved.
        // If 4th arg is present, we assume "both", otherwise "vocals".
        if (args.length > 3) {
            outputAccompanimentFilePath = args[3];
            processingParams.put("output_content", "both");
            logger.info("Accompaniment output path provided: {}", outputAccompanimentFilePath);
        } else {
            processingParams.put("output_content", "vocals");
            logger.info("No accompaniment output path provided. Only vocals will be saved by default by AudioSeparationService based on this 'output_content' setting.");
            // If we still wanted a default name for accompaniment when mode is "both" but path is missing:
            // Path inputPathObj = Paths.get(inputFilePath);
            // String baseName = inputPathObj.getFileName().toString().replaceFirst("[.][^.]+$", "");
            // outputAccompanimentFilePath = inputPathObj.getParent().resolve(baseName + "_accompaniment_sv.wav").toString();
        }
        // Note: AudioSeparationService itself defaults sample_rate to 44100.0f if not in params.

        logger.info("Starting AudioSeparationService with parameters: {}", processingParams);
        logger.info("Model Path: {}", modelPath);
        logger.info("Input File: {}", inputFilePath);
        logger.info("Output Vocals File: {}", outputVocalsFilePath);

        ProgressListener consoleListener = new ConsoleProgressListener();

        try (AudioSeparationService service = new AudioSeparationService(modelPath, processingParams)) {
            // If output_content was set to "vocals", outputAccompanimentFilePath can be null or ignored by demixFile
            // For clarity, if we are in "vocals" mode and didn't get a 4th arg, pass a placeholder or null.
            // The service's demixFile method should respect its internal output_content setting.
            // If output_content is "both" but outputAccompanimentFilePath is null, demixFile might need to handle it
            // or we ensure it's always set if mode is "both".
            
            String effectiveAccompanimentPath = outputAccompanimentFilePath;
            if ("both".equals(processingParams.get("output_content")) && effectiveAccompanimentPath == null) {
                 Path inputPathObj = Paths.get(inputFilePath);
                 String baseName = inputPathObj.getFileName().toString().replaceFirst("[.][^.]+$", "");
                 Path parentDir = inputPathObj.getParent();
                 if (parentDir == null) parentDir = Paths.get("."); // Default to current directory if no parent
                 effectiveAccompanimentPath = parentDir.resolve(baseName + "_accompaniment_sv.wav").toString();
                 logger.info("Accompaniment output path defaulted to: {}", effectiveAccompanimentPath);
            }


            service.demixFile(inputFilePath, outputVocalsFilePath, effectiveAccompanimentPath, consoleListener);
            
            logger.info("MainSv: Processing complete for {}. Vocals: {}", inputFilePath, outputVocalsFilePath);
            if (effectiveAccompanimentPath != null && "both".equals(processingParams.get("output_content"))) {
                logger.info("MainSv: Accompaniment: {}", effectiveAccompanimentPath);
            } else if ("vocals".equals(processingParams.get("output_content"))) {
                logger.info("MainSv: Accompaniment processing was skipped as per 'output_content' setting.");
            }

        } catch (Exception e) {
            logger.error("Error initializing or using AudioSeparationService: " + e.getMessage(), e);
            System.exit(1);
        }
        logger.info("MainSv finished.");
    }
}
