package id.fazli.razorpdf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class OcrFragment extends Fragment {

    private final List<TextRecognizer> recognizersPipeline = new ArrayList<>();
    private ImageView ivPreview;
    private Button btnShowCachedText;
    private File ocrCamFile;
    private Uri ocrCamUri;
    private String lastRecognizedText = null;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        resetTextCache();
                        ivPreview.setImageURI(imageUri);
                        try {
                            runMultiScriptOcrPipeline(InputImage.fromFilePath(requireContext(), imageUri));
                        } catch (java.io.IOException e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), "Failed to read selected image file", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    resetTextCache();
                    Bitmap bmp = BitmapFactory.decodeFile(ocrCamFile.getAbsolutePath());
                    if (bmp != null) {
                        ivPreview.setImageBitmap(bmp);
                        runMultiScriptOcrPipeline(InputImage.fromBitmap(bmp, 0));
                    }
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_ocr, container, false);
        ivPreview = view.findViewById(R.id.ivPreview);
        btnShowCachedText = view.findViewById(R.id.btnShowCachedText);

        // Populate the pipeline matrix with available Android on-device packages
        recognizersPipeline.clear();
        recognizersPipeline.add(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS));
        recognizersPipeline.add(TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()));
        recognizersPipeline.add(TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()));
        recognizersPipeline.add(TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()));
        recognizersPipeline.add(TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build()));

        view.findViewById(R.id.btnPickImage).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            galleryLauncher.launch(intent);
        });

        view.findViewById(R.id.btnCameraOcr).setOnClickListener(v -> {
            ocrCamFile = new File(requireContext().getCacheDir(), "ocr_capture.jpg");
            ocrCamUri = FileProvider.getUriForFile(requireContext(), "id.fazli.razorpdf.fileprovider", ocrCamFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, ocrCamUri);
            cameraLauncher.launch(intent);
        });

        btnShowCachedText.setOnClickListener(v -> {
            if (lastRecognizedText != null && !lastRecognizedText.isEmpty()) {
                showCopyableDialog(lastRecognizedText);
            }
        });

        return view;
    }

    private void resetTextCache() {
        lastRecognizedText = null;
        btnShowCachedText.setVisibility(View.GONE);
    }

    private void runMultiScriptOcrPipeline(InputImage image) {
        StringBuilder cumulativeResults = new StringBuilder();
        executePipelineStep(image, 0, cumulativeResults);
    }

    private void executePipelineStep(InputImage image, int index, StringBuilder cumulativeResults) {
        if (index >= recognizersPipeline.size()) {
            String finalOutput = cumulativeResults.toString().trim();
            if (!finalOutput.isEmpty()) {
                lastRecognizedText = finalOutput;
                btnShowCachedText.setVisibility(View.VISIBLE);
                showCopyableDialog(finalOutput);
            } else {
                Toast.makeText(getContext(), "No text detected across script libraries.", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        recognizersPipeline.get(index).process(image)
                .addOnSuccessListener(visionText -> {
                    String extracted = visionText.getText();
                    if (!extracted.isEmpty()) {
                        if (cumulativeResults.indexOf(extracted) == -1) {
                            cumulativeResults.append(extracted).append("\n\n");
                        }
                    }
                    executePipelineStep(image, index + 1, cumulativeResults);
                })
                .addOnFailureListener(e -> {
                    executePipelineStep(image, index + 1, cumulativeResults);
                });
    }

    private void showCopyableDialog(String targetText) {
        EditText etOutput = new EditText(requireContext());
        etOutput.setText(targetText);
        etOutput.setPadding(40, 40, 40, 40);
        etOutput.setTextIsSelectable(true);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Scanned Text")
                .setView(etOutput)
                .setPositiveButton("Copy All", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("OCR_Data", targetText));
                        Toast.makeText(getContext(), "Copied to Clipboard!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null).show();
    }
}