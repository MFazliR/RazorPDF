package id.fazli.razorpdf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ScannerFragment extends Fragment {

    private final List<Bitmap> scannedPages = new ArrayList<>();
    private Uri tempPhotoUri;
    private File tempFile;

    private TextView tvScanStatus;
    private Button btnCompilePdf;
    private Bitmap rawCapturedBitmap;

    private Spinner spinnerPageSize;
    private Spinner spinnerMargins;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) launchCameraIntent();
                else Toast.makeText(getContext(), "Camera access denied.", Toast.LENGTH_SHORT).show();
            }
    );

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    rawCapturedBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
                    if (rawCapturedBitmap != null) showFilterAndEnhancementDialog();
                }
            }
    );

    private final ActivityResultLauncher<Intent> saveFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri exportUri = result.getData().getData();
                    if (exportUri != null) writeScannedPagesToUri(exportUri);
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scanner, container, false);

        tvScanStatus = view.findViewById(R.id.tvScanStatus);
        btnCompilePdf = view.findViewById(R.id.btnCompilePdf);
        spinnerPageSize = view.findViewById(R.id.spinnerScannerPageSize);
        spinnerMargins = view.findViewById(R.id.spinnerScannerMargins);

        // Populate Layout Selectors
        String[] pageSizes = {"Match Aspect Ratio", "A4", "Letter"};
        String[] margins = {"No Margins (0pt)", "Narrow (72pt)", "Standard (144pt)"};
        spinnerPageSize.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, pageSizes));
        spinnerMargins.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, margins));

        view.findViewById(R.id.btnCapturePage).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnCompilePdf.setOnClickListener(v -> initiateExportNamingSequence());
        return view;
    }

    private void launchCameraIntent() {
        try {
            tempFile = new File(requireContext().getCacheDir(), "temp_scan.jpg");
            tempPhotoUri = FileProvider.getUriForFile(requireContext(), "id.fazli.razorpdf.fileprovider", tempFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, tempPhotoUri);
            cameraLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showFilterAndEnhancementDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Scan Filter Preview");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_filter_preview, null);
        ImageView ivPreview = dialogView.findViewById(R.id.ivFilterPreview);
        ivPreview.setImageBitmap(rawCapturedBitmap);

        final Bitmap[] processedBitmap = { rawCapturedBitmap };

        dialogView.findViewById(R.id.btnFilterOriginal).setOnClickListener(v -> {
            processedBitmap[0] = rawCapturedBitmap;
            ivPreview.setImageBitmap(processedBitmap[0]);
        });

        dialogView.findViewById(R.id.btnFilterDoc).setOnClickListener(v -> {
            processedBitmap[0] = applyColorEnhancement(rawCapturedBitmap, 1.2f, 15f);
            ivPreview.setImageBitmap(processedBitmap[0]);
        });

        dialogView.findViewById(R.id.btnFilterBW).setOnClickListener(v -> {
            processedBitmap[0] = convertToMonochromeMatrix(rawCapturedBitmap);
            ivPreview.setImageBitmap(processedBitmap[0]);
        });

        builder.setView(dialogView);
        builder.setPositiveButton("Keep Page", (dialog, which) -> {
            scannedPages.add(processedBitmap[0]);
            updateStatusUI();
        });
        builder.setNegativeButton("Discard", null);
        builder.show();
    }

    private Bitmap applyColorEnhancement(Bitmap src, float contrast, float brightness) {
        Bitmap cmBitmap = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Canvas canvas = new Canvas(cmBitmap);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix(new float[] {
                contrast, 0, 0, 0, brightness,
                0, contrast, 0, 0, brightness,
                0, 0, contrast, 0, brightness,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return cmBitmap;
    }

    private Bitmap convertToMonochromeMatrix(Bitmap src) {
        Bitmap bmpMonochrome = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmpMonochrome);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrix contrastMatrix = new ColorMatrix(new float[] {
                3f, 0, 0, 0, -200f,
                0, 3f, 0, 0, -200f,
                0, 0, 3f, 0, -200f,
                0, 0, 0, 1, 0
        });
        cm.postConcat(contrastMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return bmpMonochrome;
    }

    private void updateStatusUI() {
        if (!scannedPages.isEmpty()) {
            tvScanStatus.setText("Total Scanned Pages in Queue: " + scannedPages.size());
            btnCompilePdf.setVisibility(View.VISIBLE);
        } else {
            tvScanStatus.setText("No pages scanned yet.");
            btnCompilePdf.setVisibility(View.GONE);
        }
    }

    private void initiateExportNamingSequence() {
        if (scannedPages.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, "Scanned_Doc.pdf");
        saveFolderLauncher.launch(intent);
    }

    private void writeScannedPagesToUri(Uri destinationUri) {
        try {
            OutputStream os = requireContext().getContentResolver().openOutputStream(destinationUri);
            if (os == null) return;

            PDDocument document = new PDDocument();
            String selectedPageSize = spinnerPageSize.getSelectedItem().toString();
            String selectedMarginOption = spinnerMargins.getSelectedItem().toString();

            float margin = 0;
            if (selectedMarginOption.contains("72pt")) margin = 72;
            if (selectedMarginOption.contains("144pt")) margin = 144;

            for (Bitmap pageBmp : scannedPages) {
                PDRectangle pageLayout;
                boolean isFitToImage = selectedPageSize.equals("Match Aspect Ratio");

                if (isFitToImage) {
                    pageLayout = new PDRectangle(pageBmp.getWidth(), pageBmp.getHeight());
                    margin = 0;
                } else if (selectedPageSize.equals("A4")) {
                    pageLayout = PDRectangle.A4;
                } else {
                    pageLayout = PDRectangle.LETTER;
                }

                PDPage page = new PDPage(pageLayout);
                document.addPage(page);

                PDImageXObject imageXObject = JPEGFactory.createFromImage(document, pageBmp);
                PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false);

                if (isFitToImage) {
                    // LOCK FLUSH VIA GRAPHICS TRANSFORMATION MATRIX
                    // Bypasses float coordinates entirely and stretches the bitmap vector edge-to-edge
                    com.tom_roush.pdfbox.util.Matrix transformMatrix = com.tom_roush.pdfbox.util.Matrix.getScaleInstance(pageLayout.getWidth(), pageLayout.getHeight());
                    contentStream.drawImage(imageXObject, transformMatrix);
                } else {
                    float printableWidth = pageLayout.getWidth() - (2 * margin);
                    float printableHeight = pageLayout.getHeight() - (2 * margin);

                    float imgRatio = (float) pageBmp.getWidth() / pageBmp.getHeight();
                    float pageRatio = printableWidth / printableHeight;

                    float drawWidth, drawHeight;
                    if (imgRatio > pageRatio) {
                        drawWidth = printableWidth;
                        drawHeight = printableWidth / imgRatio;
                    } else {
                        drawHeight = printableHeight;
                        drawWidth = printableHeight * imgRatio;
                    }
                    float xOffset = margin + (printableWidth - drawWidth) / 2f;
                    float yOffset = margin + (printableHeight - drawHeight) / 2f;

                    contentStream.drawImage(imageXObject, xOffset, yOffset, drawWidth, drawHeight);
                }

                contentStream.close();
                pageBmp.recycle();
            }

            document.save(os);
            document.close();
            os.close();

            scannedPages.clear();
            updateStatusUI();
            Toast.makeText(getContext(), "Scanner PDF compiled edge-to-edge!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}