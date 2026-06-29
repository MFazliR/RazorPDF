package id.fazli.razorpdf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class PdfToolsFragment extends Fragment {

    private int currentAction = 0; // 1: Merge, 2: Split, 3: Compress, 4: Convert
    private final List<Uri> selectedUris = new ArrayList<>();
    private String customSplitRange = "";

    private Spinner spinnerPageSize;
    private Spinner spinnerMargins;

    // 1. File Selector Input Launcher
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedUris.clear();
                    if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            selectedUris.add(result.getData().getClipData().getItemAt(i).getUri());
                        }
                    } else if (result.getData().getData() != null) {
                        selectedUris.add(result.getData().getData());
                    }
                    showActionPreviewAndConfigurationDialog();
                }
            }
    );

    // 2. Custom Save Destination Folder and Filename Launcher
    private final ActivityResultLauncher<Intent> saveFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri exportUri = result.getData().getData();
                    if (exportUri != null) {
                        processFileExecution(exportUri);
                    }
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pdf_tools, container, false);

        spinnerPageSize = view.findViewById(R.id.spinnerPageSize);
        spinnerMargins = view.findViewById(R.id.spinnerMargins);

        // Populate configuration drop-downs
        String[] pageSizes = {"Match Aspect Ratio", "A4", "Letter"};
        String[] margins = {"No Margins (0pt)", "Narrow (72pt)", "Standard (144pt)"};

        spinnerPageSize.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, pageSizes));
        spinnerMargins.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, margins));

        view.findViewById(R.id.btnMerge).setOnClickListener(v -> triggerPicker("application/pdf", true, 1));
        view.findViewById(R.id.btnSplit).setOnClickListener(v -> triggerPicker("application/pdf", false, 2));
        view.findViewById(R.id.btnCompress).setOnClickListener(v -> triggerPicker("application/pdf", false, 3));
        view.findViewById(R.id.btnConvert).setOnClickListener(v -> triggerPicker("image/*", true, 4));

        return view;
    }

    private void triggerPicker(String mimeType, boolean allowMultiple, int actionId) {
        currentAction = actionId;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Input Files"));
    }

    private void showActionPreviewAndConfigurationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("📄 PDF Tool Execution Preview");

        // Dynamic clean text building to preview targeted items
        StringBuilder previewText = new StringBuilder();
        previewText.append("Ready to process the following selections:\n");
        previewText.append("===============================\n");
        for (int i = 0; i < selectedUris.size(); i++) {
            previewText.append(String.format("%d. %s\n", i + 1, selectedUris.get(i).getLastPathSegment()));
        }
        previewText.append("===============================\n");
        previewText.append("\nClick PROCEED to select a folder location and name your output file.");

        if (currentAction == 2) { // SPLIT ACTION CONFIG
            final EditText etRange = new EditText(requireContext());
            etRange.setHint("Enter Range (e.g. 1-5 or 6-14)");
            builder.setView(etRange);
            builder.setMessage(previewText.toString());
            builder.setPositiveButton("Proceed", (dialog, which) -> {
                customSplitRange = etRange.getText().toString().trim();
                triggerSaveLocationPicker("Split_Segment_Output.pdf");
            });
        } else {
            builder.setMessage(previewText.toString());
            String suggestedName = "RazorPDF_Output.pdf";
            if (currentAction == 1) suggestedName = "Merged_Document.pdf";
            if (currentAction == 3) suggestedName = "Compressed_Document.pdf";
            if (currentAction == 4) suggestedName = "Images_Converted.pdf";

            String finalSuggestedName = suggestedName;
            builder.setPositiveButton("Proceed", (dialog, which) -> triggerSaveLocationPicker(finalSuggestedName));
        }

        builder.setNegativeButton("Cancel Selection", (dialog, which) -> selectedUris.clear());
        builder.show();
    }

    private void triggerSaveLocationPicker(String suggestedName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        saveFolderLauncher.launch(intent);
    }

    private void processFileExecution(Uri destinationUri) {
        try {
            OutputStream outputStream = requireContext().getContentResolver().openOutputStream(destinationUri);
            if (outputStream == null) return;

            if (currentAction == 1) { // MERGE
                PDFMergerUtility merger = new PDFMergerUtility();
                merger.setDestinationStream(outputStream);
                for (Uri uri : selectedUris) {
                    InputStream is = requireContext().getContentResolver().openInputStream(uri);
                    merger.addSource(is);
                }
                merger.mergeDocuments(null);
                Toast.makeText(getContext(), "PDFs Successfully Merged & Saved!", Toast.LENGTH_LONG).show();

            } else if (currentAction == 2) { // SPLIT
                String[] parts = customSplitRange.split("-");
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());

                InputStream is = requireContext().getContentResolver().openInputStream(selectedUris.get(0));
                PDDocument sourceDoc = PDDocument.load(is);
                PDDocument newDoc = new PDDocument();

                int total = sourceDoc.getNumberOfPages();
                int zeroStart = Math.max(0, start - 1);
                int zeroEnd = Math.min(total - 1, end - 1);

                for (int i = zeroStart; i <= zeroEnd; i++) {
                    newDoc.addPage(sourceDoc.getPage(i));
                }
                newDoc.save(outputStream);
                newDoc.close();
                sourceDoc.close();
                Toast.makeText(getContext(), "PDF Segment Split Finished!", Toast.LENGTH_LONG).show();

            } else if (currentAction == 3) { // COMPRESSION PIPELINE (Fixes White Bar / Ghost Layout Bugs)
                InputStream is = requireContext().getContentResolver().openInputStream(selectedUris.get(0));
                PDDocument doc = PDDocument.load(is, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());

                for (PDPage page : doc.getPages()) {
                    com.tom_roush.pdfbox.pdmodel.PDResources resources = page.getResources();
                    if (resources == null) continue;

                    for (com.tom_roush.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                        if (resources.isImageXObject(name)) {
                            PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                            Bitmap bmp = image.getImage();
                            if (bmp != null) {
                                // Downsample to a consistent resolution ceiling
                                int maxDimension = 1400;
                                if (bmp.getWidth() > maxDimension || bmp.getHeight() > maxDimension) {
                                    float ratio = Math.min((float) maxDimension / bmp.getWidth(), (float) maxDimension / bmp.getHeight());
                                    bmp = Bitmap.createScaledBitmap(bmp, (int)(bmp.getWidth() * ratio), (int)(bmp.getHeight() * ratio), true);
                                }

                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                bmp.compress(Bitmap.CompressFormat.JPEG, 45, baos); // Balanced quality profile
                                byte[] compressedBytes = baos.toByteArray();

                                PDImageXObject compressedImage = JPEGFactory.createFromByteArray(doc, compressedBytes);

                                // Directly swap the object reference inside the resource tree map
                                resources.put(name, compressedImage);
                                bmp.recycle();
                            }
                        }
                    }
                    // Save state markers explicitly inside the individual page object map
                    if (page.getCOSObject() != null) {
                        page.getCOSObject().setNeedToBeUpdated(true);
                    }
                }

                if (doc.getDocumentCatalog() != null) {
                    doc.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
                }

                doc.save(outputStream);
                doc.close();
                Toast.makeText(getContext(), "PDF Compressed Successfully without Artifacts!", Toast.LENGTH_LONG).show();

            } else if (currentAction == 4) { // DYNAMIC IMAGE TO PDF CONVERSION
                PDDocument doc = new PDDocument();
                String selectedPageSize = spinnerPageSize.getSelectedItem().toString();
                String selectedMarginOption = spinnerMargins.getSelectedItem().toString();

                float margin = 0;
                if (selectedMarginOption.contains("72pt")) margin = 72;
                if (selectedMarginOption.contains("144pt")) margin = 144;

                for (Uri imgUri : selectedUris) {
                    InputStream is = requireContext().getContentResolver().openInputStream(imgUri);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap == null) continue;

                    PDRectangle pageLayout;
                    if (selectedPageSize.equals("Match Aspect Ratio")) {
                        pageLayout = new PDRectangle(bitmap.getWidth(), bitmap.getHeight());
                        margin = 0; // Enforce perfect edge clipping
                    } else if (selectedPageSize.equals("A4")) {
                        pageLayout = PDRectangle.A4;
                    } else {
                        pageLayout = PDRectangle.LETTER;
                    }

                    PDPage page = new PDPage(pageLayout);
                    doc.addPage(page);

                    // FIX: Scrub white canvas initialization tags out of the page structural tree
                    com.tom_roush.pdfbox.cos.COSDictionary pageDict = page.getCOSObject();
                    if (pageDict != null) {
                        pageDict.removeItem(com.tom_roush.pdfbox.cos.COSName.BACKGROUND);
                    }

                    float printableWidth = pageLayout.getWidth() - (2 * margin);
                    float printableHeight = pageLayout.getHeight() - (2 * margin);

                    float imgRatio = (float) bitmap.getWidth() / bitmap.getHeight();
                    float pageRatio = printableWidth / printableHeight;

                    float drawWidth, drawHeight;
                    if (selectedPageSize.equals("Match Aspect Ratio")) {
                        drawWidth = pageLayout.getWidth();
                        drawHeight = pageLayout.getHeight();
                    } else {
                        if (imgRatio > pageRatio) {
                            drawWidth = printableWidth;
                            drawHeight = printableWidth / imgRatio;
                        } else {
                            drawHeight = printableHeight;
                            drawWidth = printableHeight * imgRatio;
                        }
                    }

                    float xOffset = margin + (printableWidth - drawWidth) / 2f;
                    float yOffset = margin + (printableHeight - drawHeight) / 2f;

                    PDImageXObject imgXObject = JPEGFactory.createFromImage(doc, bitmap);
                    PDPageContentStream contentStream = new PDPageContentStream(doc, page);
                    contentStream.drawImage(imgXObject, xOffset, yOffset, drawWidth, drawHeight);
                    contentStream.close();
                    bitmap.recycle();
                }
                doc.save(outputStream);
                doc.close();
                Toast.makeText(getContext(), "Images compiled directly flush to layout!", Toast.LENGTH_LONG).show();
            }

            outputStream.close();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Execution Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}