package com.example.audioseparator;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption("o", "output_dir", true, "Output directory (default: separated_out)");
        options.addOption(Option.builder("m")
                .longOpt("model_path")
                .hasArg()
                .required(true)
                .desc("Path to the ONNX model file (required)")
                .build());
        options.addOption("d", "no-denoise", false, "Disable denoising (denoising is enabled if this flag is not set)");
        options.addOption("M", "margin_samples", true, "Margin in samples (default: 44100, i.e., 1 second at 44.1kHz)");
        options.addOption("c", "chunk_seconds", true, "Chunk size in seconds (default: 45. If 0, process whole file)");
        
        options.addOption("F", "n_fft", true, "N_FFT size (default: 6144)");
        options.addOption("T", "dim_t_exponent", true, "Exponent for STFT time frames (dim_t = 2^exponent) (default: 8 -> 256 frames)");
        options.addOption("f", "dim_f", true, "Dimension F for STFT frequency bins (default: 2048)");
        // options.addOption("r", "sample_rate", true, "Target sample rate for processing (default: 44100.0f)"); // Removed
        options.addOption(null, "output_content", true, "Specify output content: 'vocals' or 'both'. Default: 'both'");
        options.addOption("h", "help", false, "Display this help message");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            logger.error("Error parsing command line arguments: {}", e.getMessage());
            formatter.printHelp("java -jar audio-separator.jar [options] <file1.wav> [file2.wav...]", options);
            System.exit(1);
            return;
        }

        if (cmd.hasOption("help")) {
            formatter.printHelp("java -jar audio-separator.jar [options] <file1.wav> [file2.wav...]", options);
            System.exit(0);
            return;
        }

        String[] inputFiles = cmd.getArgs();
        if (inputFiles.length == 0) {
            logger.error("No input files provided.");
            formatter.printHelp("java -jar audio-separator.jar [options] <file1.wav> [file2.wav...]", options);
            System.exit(1);
            return;
        }

        String outputDir = cmd.getOptionValue("output_dir", "separated_out");
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (Exception e) {
            logger.error("Could not create output directory: {}", outputDir, e);
            System.exit(1);
            return;
        }
        
        Map<String, Object> processingArgs = new HashMap<>();
        processingArgs.put("model_path", cmd.getOptionValue("m"));
        // Denoising is true by default in original python script, so "no-denoise" flag means set denoiseEnabled to false
        processingArgs.put("denoise", !cmd.hasOption("d")); 
        float targetSampleRate = 44100.0f; // Hardcoded sample rate
        processingArgs.put("sample_rate", targetSampleRate); // Hardcoded for AudioSeparator
        // Default margin is 1s * 44100 = 44100 samples
        processingArgs.put("margin", Integer.parseInt(cmd.getOptionValue("margin_samples", "44100"))); 
        processingArgs.put("chunks", Integer.parseInt(cmd.getOptionValue("chunk_seconds", "45")));

        String outputContent = cmd.getOptionValue("output_content", "both").toLowerCase();
        if (!outputContent.equals("vocals") && !outputContent.equals("both")) {
            logger.warn("Invalid value for output_content: '{}'. Defaulting to 'both'.", outputContent);
            outputContent = "both";
        }
        processingArgs.put("output_content", outputContent);


        AudioProcessor audioProcessor;
        try {
            int n_fft = Integer.parseInt(cmd.getOptionValue("F", "6144"));
            int dim_t_exponent = Integer.parseInt(cmd.getOptionValue("T", "8"));
            int dim_t_actual = (int) Math.pow(2, dim_t_exponent);
            int dim_f = Integer.parseInt(cmd.getOptionValue("f", "2048"));
            int hop_length = n_fft / 4; // Common default
            
            audioProcessor = new AudioProcessor(n_fft, hop_length, dim_f, dim_t_actual);
            logger.info("AudioProcessor initialized with: n_fft={}, hop_length={}, dim_f={}, dim_t_actual={}", n_fft, hop_length, dim_f, dim_t_actual);
        } catch (NumberFormatException e) {
            logger.error("Invalid number format for STFT parameters.", e);
            System.exit(1);
            return;
        }


        for (String filePath : inputFiles) {
            File inputFile = new File(filePath);
            if (!inputFile.exists() || !inputFile.isFile()) {
                logger.error("Input file not found or is not a file: {}", filePath);
                continue;
            }
            logger.info("Processing file: {}", filePath);

            try {
                float[][] audioData = AudioIOUtils.readWavFile(filePath, targetSampleRate);
                if (audioData == null) {
                    logger.error("Could not read audio from file: {}", filePath);
                    continue;
                }

                List<float[][]> separatedTracks;
                try (AudioSeparator separator = new AudioSeparator(processingArgs, audioProcessor)) {
                    separatedTracks = separator.separate(audioData, null); // Added null for ProgressListener
                } // AudioSeparator.close() called automatically

                if (separatedTracks != null && separatedTracks.size() == 2) {
                    float[][] vocals = separatedTracks.get(0);
                    float[][] noVocals = separatedTracks.get(1);

                    String baseName = inputFile.getName();
                    int dotIndex = baseName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        baseName = baseName.substring(0, dotIndex);
                    }

                    String vocalsPath = Paths.get(outputDir, baseName + "_vocals.wav").toString();
                    String noVocalsPath = Paths.get(outputDir, baseName + "_no_vocals.wav").toString();

                    AudioIOUtils.writeWavFile(vocalsPath, vocals, targetSampleRate);
                    logger.info("Vocals output saved to: {}", vocalsPath);

                    if ("both".equals(processingArgs.get("output_content"))) {
                        AudioIOUtils.writeWavFile(noVocalsPath, noVocals, targetSampleRate);
                        logger.info("Accompaniment output saved to: {}", noVocalsPath);
                    } else {
                        logger.info("Accompaniment output skipped as per output_content setting.");
                    }
                    logger.info("Successfully separated tracks for {}.", filePath);
                } else {
                    logger.error("Separation failed or returned incorrect number of tracks for file: {}", filePath);
                }

            } catch (Exception e) {
                logger.error("An error occurred while processing file {}: {}", filePath, e.getMessage(), e);
            }
        }
        logger.info("All files processed.");
    }
}
