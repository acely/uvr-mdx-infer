package com.example.audioseparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AudioSeparationService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AudioSeparationService.class);
    private static final float SERVICE_SAMPLE_RATE = 44100.0f;

    private AudioProcessor audioProcessor;
    private AudioSeparator audioSeparator;
    private Map<String, Object> processingParams;

    /**
     * Constructor for AudioSeparationService.
     *
     * @param modelPath Path to the ONNX model file.
     * @param params    Configuration parameters for processing.
     * @throws Exception If initialization fails.
     */
    public AudioSeparationService(String modelPath, Map<String, Object> params) throws Exception {
        this.processingParams = new HashMap<>(params); // Store a copy

        // Initialize AudioProcessor
        try {
            int n_fft = ((Number) this.processingParams.getOrDefault("n_fft", 6144)).intValue();
            int dim_t_exponent = ((Number) this.processingParams.getOrDefault("dim_t_exponent", 8)).intValue();
            int dim_t_actual = (int) Math.pow(2, dim_t_exponent);
            int dim_f = ((Number) this.processingParams.getOrDefault("dim_f", 2048)).intValue();
            int hop_length = n_fft / 4; // Common default

            this.audioProcessor = new AudioProcessor(n_fft, hop_length, dim_f, dim_t_actual);
            logger.info("AudioProcessor initialized with: n_fft={}, hop_length={}, dim_f={}, dim_t_actual={}",
                    n_fft, hop_length, dim_f, dim_t_actual);
        } catch (Exception e) {
            logger.error("Failed to initialize AudioProcessor.", e);
            throw new Exception("AudioProcessor initialization failed.", e);
        }

        // Ensure required parameters for AudioSeparator are set
        this.processingParams.put("model_path", Objects.requireNonNull(modelPath, "Model path cannot be null."));
        this.processingParams.putIfAbsent("sample_rate", SERVICE_SAMPLE_RATE); // Enforce service sample rate
        this.processingParams.putIfAbsent("output_content", "both"); // Default to "both"

        // Initialize AudioSeparator
        try {
            this.audioSeparator = new AudioSeparator(this.processingParams, this.audioProcessor);
            logger.info("AudioSeparator initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize AudioSeparator.", e);
            // Clean up audioProcessor if audioSeparator fails
            this.audioProcessor = null; // Or add a close method to AudioProcessor if it held resources
            throw new Exception("AudioSeparator initialization failed.", e);
        }
        logger.info("AudioSeparationService initialized successfully.");
    }

    /**
     * Separates an audio file into vocals and accompaniment.
     *
     * @param inputFilePath             Path to the input audio file.
     * @param outputVocalsFilePath      Path to save the vocals track.
     * @param outputAccompanimentFilePath Path to save the accompaniment track.
     * @throws Exception If any step of the demixing process fails.
     */
    public void demixFile(String inputFilePath, String outputVocalsFilePath, String outputAccompanimentFilePath) throws Exception {
        logger.info("Starting demixing process for file: {}", inputFilePath);

        float[][] audioData;
        try {
            audioData = AudioIOUtils.readWavFile(inputFilePath, SERVICE_SAMPLE_RATE);
            if (audioData == null) {
                throw new IOException("AudioIOUtils.readWavFile returned null for: " + inputFilePath);
            }
            logger.info("Successfully read and resampled input file: {}", inputFilePath);
        } catch (Exception e) {
            logger.error("Failed to read input audio file: {}", inputFilePath, e);
            throw new Exception("Failed to read input audio file: " + inputFilePath, e);
        }

        List<float[][]> separatedTracks;
        try {
            separatedTracks = audioSeparator.separate(audioData);
            if (separatedTracks == null || separatedTracks.size() < 2) {
                 throw new Exception("Audio separation did not return the expected number of tracks.");
            }
            logger.info("Audio separation successful for: {}", inputFilePath);
        } catch (Exception e) {
            logger.error("Error during audio separation for file: {}", inputFilePath, e);
            throw new Exception("Error during audio separation for file: " + inputFilePath, e);
        }

        float[][] vocalsAudio = separatedTracks.get(0);
        float[][] accompanimentAudio = separatedTracks.get(1);

        try {
            AudioIOUtils.writeWavFile(outputVocalsFilePath, vocalsAudio, SERVICE_SAMPLE_RATE);
            logger.info("Vocals track saved to: {}", outputVocalsFilePath);
        } catch (Exception e) {
            logger.error("Failed to write vocals audio file: {}", outputVocalsFilePath, e);
            throw new Exception("Failed to write vocals audio file: " + outputVocalsFilePath, e);
        }

        String outputContentMode = (String) this.processingParams.getOrDefault("output_content", "both");

        if ("both".equalsIgnoreCase(outputContentMode)) {
            if (accompanimentAudio != null && accompanimentAudio.length > 0 && accompanimentAudio[0].length > 0) {
                try {
                    AudioIOUtils.writeWavFile(outputAccompanimentFilePath, accompanimentAudio, SERVICE_SAMPLE_RATE);
                    logger.info("Accompaniment track saved to: {}", outputAccompanimentFilePath);
                } catch (Exception e) {
                    logger.error("Failed to write accompaniment audio file: {}", outputAccompanimentFilePath, e);
                    throw new Exception("Failed to write accompaniment audio file: " + outputAccompanimentFilePath, e);
                }
            } else {
                logger.info("Accompaniment audio is null or empty; not saving accompaniment track.");
            }
        } else {
            logger.info("Accompaniment output skipped as per 'output_content' setting ('{}').", outputContentMode);
        }

        logger.info("Demixing process completed for file: {}", inputFilePath);
    }

    /**
     * Closes the AudioSeparator resources.
     *
     * @throws IOException if an I/O error occurs during closing.
     */
    @Override
    public void close() throws IOException {
        logger.info("Closing AudioSeparationService.");
        if (this.audioSeparator != null) {
            try {
                this.audioSeparator.close(); // AudioSeparator also implements AutoCloseable
            } catch (Exception e) { // Catching general Exception as OrtSession.close() throws OrtException
                logger.error("Error closing AudioSeparator: " + e.getMessage(), e);
                // Depending on policy, might rethrow as IOException or a custom runtime exception
                if (e instanceof IOException) {
                    throw (IOException) e;
                } else {
                    throw new IOException("Failed to close AudioSeparator due to underlying error.", e);
                }
            }
            this.audioSeparator = null;
        }
        // AudioProcessor currently doesn't have resources to close, but if it did:
        // if (this.audioProcessor != null && this.audioProcessor instanceof AutoCloseable) {
        //     try { ((AutoCloseable)this.audioProcessor).close(); } catch(Exception e) { logger.error("Error closing AudioProcessor", e); }
        // }
        this.audioProcessor = null;
        logger.info("AudioSeparationService closed.");
    }
}
