package com.example.audioseparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioIOUtils {

    private static final Logger logger = LoggerFactory.getLogger(AudioIOUtils.class);

    public static float[][] readWavFile(String filePath, float targetSampleRate) {
        logger.info("Reading WAV file: {}", filePath);
        try {
            File wavFile = new File(filePath);
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(wavFile);
            AudioFormat originalFormat = audioInputStream.getFormat();
            logger.debug("Original format: {}", originalFormat);

            AudioFormat targetFormat = new AudioFormat(
                    targetSampleRate,
                    originalFormat.getSampleSizeInBits(),
                    originalFormat.getChannels(), // Keep original channels for now, convert to stereo later if mono
                    originalFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) || originalFormat.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED),
                    originalFormat.isBigEndian()
            );

            // Resample if necessary
            if (originalFormat.getSampleRate() != targetSampleRate) {
                logger.info("Resampling from {} Hz to {} Hz", originalFormat.getSampleRate(), targetSampleRate);
                if (!AudioSystem.isConversionSupported(targetFormat, originalFormat)) {
                    logger.warn("Direct conversion for resampling not supported. Trying intermediate PCM conversion for format: {}", originalFormat);
                    // If direct conversion is not supported, try converting to a standard PCM format first
                    AudioFormat intermediatePcmFormat = new AudioFormat(
                            originalFormat.getSampleRate(), // Keep original sample rate for this step
                            16, // Standard sample size
                            originalFormat.getChannels(),
                            true, // Signed
                            false // Little-endian (common for PCM)
                    );
                    if (AudioSystem.isConversionSupported(intermediatePcmFormat, originalFormat)) {
                        audioInputStream = AudioSystem.getAudioInputStream(intermediatePcmFormat, audioInputStream);
                        originalFormat = audioInputStream.getFormat(); // Update originalFormat to the intermediate format
                        logger.debug("Converted to intermediate PCM format: {}", originalFormat);
                    } else {
                        String errorMsg = String.format("Resampling from %s to %s is not supported, even with intermediate PCM conversion.", originalFormat, targetFormat);
                        logger.error(errorMsg);
                        throw new UnsupportedAudioFileException(errorMsg);
                    }
                }
                // Now attempt resampling to the target sample rate
                 targetFormat = new AudioFormat(
                    targetSampleRate,
                    originalFormat.getSampleSizeInBits(), // Use bits from (potentially intermediate) original format
                    originalFormat.getChannels(), 
                    originalFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) || originalFormat.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED),
                    originalFormat.isBigEndian()
                );

                if (AudioSystem.isConversionSupported(targetFormat, originalFormat)) {
                    audioInputStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream);
                    logger.debug("Resampled to format: {}", audioInputStream.getFormat());
                } else {
                     String errorMsg = String.format("Resampling from %s to %s is not supported.", originalFormat, targetFormat);
                    logger.error(errorMsg);
                    throw new UnsupportedAudioFileException(errorMsg);
                }
            }


            AudioFormat currentFormat = audioInputStream.getFormat();
            logger.debug("Format after potential resampling: {}", currentFormat);

            // Read all bytes from the audio input stream
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 4]; // Buffer size
            int bytesRead;
            while ((bytesRead = audioInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            byte[] audioBytes = byteArrayOutputStream.toByteArray();
            audioInputStream.close();

            int frameSize = currentFormat.getFrameSize();
            int numChannels = currentFormat.getChannels();
            int sampleSizeInBytes = currentFormat.getSampleSizeInBits() / 8;
            int numFrames = audioBytes.length / frameSize;

            float[][] floatData;
            if (numChannels == 1) {
                logger.info("Audio is mono. Converting to stereo.");
                floatData = new float[2][numFrames];
                for (int i = 0; i < numFrames; i++) {
                    int byteOffset = i * frameSize;
                    float sample = bytesToFloat(audioBytes, byteOffset, sampleSizeInBytes, currentFormat.isBigEndian(), currentFormat.getEncoding());
                    floatData[0][i] = sample; // Left channel
                    floatData[1][i] = sample; // Right channel (duplicate)
                }
            } else if (numChannels == 2) {
                logger.info("Audio is stereo.");
                floatData = new float[2][numFrames];
                for (int i = 0; i < numFrames; i++) {
                    int byteOffset = i * frameSize;
                    floatData[0][i] = bytesToFloat(audioBytes, byteOffset, sampleSizeInBytes, currentFormat.isBigEndian(), currentFormat.getEncoding()); // Left
                    floatData[1][i] = bytesToFloat(audioBytes, byteOffset + sampleSizeInBytes, sampleSizeInBytes, currentFormat.isBigEndian(), currentFormat.getEncoding()); // Right
                }
            } else {
                String errorMsg = "Unsupported number of channels: " + numChannels + ". Only mono or stereo is supported.";
                logger.error(errorMsg);
                throw new UnsupportedAudioFileException(errorMsg);
            }

            logger.info("Successfully read and processed WAV file: {}", filePath);
            return floatData;

        } catch (UnsupportedAudioFileException e) {
            logger.error("Unsupported audio file: {} - {}", filePath, e.getMessage(), e);
        } catch (IOException e) {
            logger.error("Error reading WAV file: {} - {}", filePath, e.getMessage(), e);
        }
        return null; // Return null or throw a custom exception if an error occurs
    }

    private static float bytesToFloat(byte[] bytes, int offset, int sampleSizeInBytes, boolean isBigEndian, AudioFormat.Encoding encoding) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, offset, sampleSizeInBytes);
        if (isBigEndian) {
            byteBuffer.order(ByteOrder.BIG_ENDIAN);
        } else {
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        if (encoding.equals(AudioFormat.Encoding.PCM_SIGNED)) {
            if (sampleSizeInBytes == 1) { // 8-bit signed
                return (float) byteBuffer.get() / 128.0f;
            } else if (sampleSizeInBytes == 2) { // 16-bit signed
                return (float) byteBuffer.getShort() / 32768.0f;
            } else if (sampleSizeInBytes == 3) { // 24-bit signed (packed)
                int value = 0;
                if (isBigEndian) {
                    value = ((bytes[offset] & 0xFF) << 16) | ((bytes[offset + 1] & 0xFF) << 8) | (bytes[offset + 2] & 0xFF);
                } else {
                    value = (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8) | ((bytes[offset + 2] & 0xFF) << 16);
                }
                if ((value & 0x800000) != 0) { // Check sign bit
                    value |= 0xFF000000; // Extend sign for negative numbers
                }
                return (float) value / 8388608.0f;
            } else if (sampleSizeInBytes == 4) { // 32-bit signed
                 return (float) byteBuffer.getInt() / 2147483648.0f;
            }
        } else if (encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            if (sampleSizeInBytes == 1) { // 8-bit unsigned
                return ((float) (byteBuffer.get() & 0xFF) - 128.0f) / 128.0f;
            } else if (sampleSizeInBytes == 2) { // 16-bit unsigned
                return ((float) (byteBuffer.getShort() & 0xFFFF) - 32768.0f) / 32768.0f;
            }
            // Add other unsigned formats if needed
        } else if (encoding.equals(AudioFormat.Encoding.PCM_FLOAT)) {
            if (sampleSizeInBytes == 4) { // 32-bit float
                return byteBuffer.getFloat();
            } else if (sampleSizeInBytes == 8) { // 64-bit float (double)
                return (float) byteBuffer.getDouble();
            }
        }
        logger.warn("Unsupported audio encoding for byte-to-float conversion: {}, sampleSize: {}", encoding, sampleSizeInBytes);
        return 0.0f; // Default or throw error
    }


    public static void writeWavFile(String filePath, float[][] audioData, float sampleRate) {
        logger.info("Preparing to write WAV file: {}", filePath);
        if (audioData == null || audioData.length == 0) {
            logger.error("Audio data is null or empty for file: {}", filePath);
            return;
        }
        if (audioData.length != 2) {
            logger.error("Audio data must have 2 channels (stereo) for file: {}. Found {} channels.", filePath, audioData.length);
            return;
        }

        int numFrames = audioData[0].length;
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false); // Stereo, 16-bit PCM, little-endian
        logger.debug("Target format for writing: {}", format);

        byte[] byteData = new byte[numFrames * format.getFrameSize()];
        ByteBuffer byteBuffer = ByteBuffer.wrap(byteData);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN); // WAV standard is little-endian for PCM

        for (int i = 0; i < numFrames; i++) {
            // Left channel
            float leftSampleFloat = audioData[0][i];
            short leftSampleShort = (short) Math.max(-32768, Math.min(32767, Math.round(leftSampleFloat * 32767.0f)));
            byteBuffer.putShort(leftSampleShort);

            // Right channel
            float rightSampleFloat = audioData[1][i];
            short rightSampleShort = (short) Math.max(-32768, Math.min(32767, Math.round(rightSampleFloat * 32767.0f)));
            byteBuffer.putShort(rightSampleShort);
        }

        try (AudioInputStream audioInputStream = new AudioInputStream(
                new ByteArrayInputStream(byteData),
                format,
                numFrames)) {

            File outputFile = new File(filePath);
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile);
            logger.info("Successfully wrote WAV file: {}", filePath);

        } catch (IOException e) {
            logger.error("Error writing WAV file: {} - {}", filePath, e.getMessage(), e);
        }
    }
}
