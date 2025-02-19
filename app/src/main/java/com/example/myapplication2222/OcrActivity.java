package com.example.myapplication2222;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OcrActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int REQUEST_CODE_CAPTURE_IMAGE = 1001;

    private TextView resultTextView;
    private ImageView imageView;
    private ProgressBar progressBar;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private File photoFile;
    private ProcessCameraProvider cameraProvider;
    private int previewWidth, previewHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ocr);

        // Initialize UI components
        Button captureButton = findViewById(R.id.captureButton);
        Button recaptureButton = findViewById(R.id.recaptureButton);
        resultTextView = findViewById(R.id.resultTextView);
        imageView = findViewById(R.id.imageView);
        progressBar = findViewById(R.id.progressBar);
        previewView = findViewById(R.id.previewView);

        // Initialize the camera executor
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Check for camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
        }

        // Set button listeners
        captureButton.setOnClickListener(v -> takePhoto());
        recaptureButton.setOnClickListener(v -> startCamera());
    }

    // Check if all necessary permissions are granted
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // Start the camera and bind to the lifecycle
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("OcrActivity", "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Bind the preview and image capture to the camera lifecycle
    private void bindPreview(ProcessCameraProvider cameraProvider) {
        previewView.post(() -> {
            previewWidth = previewView.getMeasuredWidth();
            previewHeight = previewView.getMeasuredHeight();

            Preview preview = new Preview.Builder()
                    .setTargetResolution(new Size(previewWidth, previewHeight))
                    .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            imageCapture = new ImageCapture.Builder()
                    .setTargetResolution(new Size(previewWidth, previewHeight))
                    .setTargetRotation(previewView.getDisplay().getRotation())
                    .build();

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageCapture);
        });
    }

    // Capture a photo and save it
    private void takePhoto() {
        if (imageCapture == null) return;

        photoFile = new File(getExternalFilesDir(null), "photo.jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                runOnUiThread(() -> {
                    Toast.makeText(OcrActivity.this, "사진이 저장되었습니다.", Toast.LENGTH_SHORT).show();
                    updateImageView(Uri.fromFile(photoFile));
                    cameraProvider.unbindAll();
                });
                Log.d("OcrActivity", "Photo saved at: " + photoFile.getAbsolutePath());
                processImage(Uri.fromFile(photoFile));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(OcrActivity.this, "사진 저장 실패", Toast.LENGTH_SHORT).show());
                Log.e("OcrActivity", "Photo capture failed", exception);
            }
        });
    }

    // Update the ImageView with the captured photo
    private void updateImageView(Uri photoUri) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(photoUri));
            Bitmap rotatedBitmap = rotateBitmap(bitmap, 90); // Rotate the image by 90 degrees
            imageView.setImageBitmap(rotatedBitmap);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(OcrActivity.this, "이미지 로드 실패", Toast.LENGTH_SHORT).show();
        }
    }

    // Rotate the bitmap by the specified degrees
    private Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    // Process the captured image and perform OCR
    private void processImage(Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        String recognizedText = text.getText();
                        Log.d("OcrActivity", "Recognized text: " + recognizedText);

                        // Define patterns for date of birth
                        String[] dobPatterns = {
                                "\\b(19\\d{2}|20\\d{2})[./-]?(0[1-9]|1[0-2])[./-]?(0[1-9]|[12][0-9]|3[01])\\b",
                                "\\b(\\d{4})[년\\s-](0[1-9]|1[0-2])[월\\s-](0[1-9]|[12][0-9]|3[01])[일\\s-]?\\b",
                                "\\b(\\d{6})\\b"  // Add more patterns as needed
                        };

                        String dob = findDateOfBirth(recognizedText, dobPatterns);

                        if (dob != null) {
                            boolean isAdult = !isMinor(dob);
                            runOnUiThread(() -> resultTextView.setText(isAdult ? "성인입니다." : "미성년자입니다."));
                            sendResult(isAdult);
                        } else {
                            runOnUiThread(() -> resultTextView.setText("생년월일을 찾을 수 없습니다."));
                            sendResult(false);
                        }
                    })
                    .addOnFailureListener(e -> {
                        runOnUiThread(() -> Toast.makeText(OcrActivity.this, "텍스트 인식 실패", Toast.LENGTH_SHORT).show());
                        Log.e("OcrActivity", "Text recognition failed", e);
                    });
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "이미지 처리 실패", Toast.LENGTH_SHORT).show();
        }
    }

    // Find the date of birth in the recognized text based on regex patterns
    private String findDateOfBirth(String text, String[] patterns) {
        for (String pattern : patterns) {
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(text);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    // Check if the date of birth indicates a minor
    private boolean isMinor(String dobString) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Date dob;
        try {
            if (dobString.length() == 6) {
                dob = new SimpleDateFormat("yyMMdd", Locale.getDefault()).parse(dobString);
                Calendar cal = Calendar.getInstance();
                cal.setTime(dob);
                int year = cal.get(Calendar.YEAR);
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                if (year < currentYear - 100) {
                    year += 100;
                }
                cal.set(Calendar.YEAR, year);
                dob = cal.getTime();
            } else {
                dob = sdf.parse(dobString.replaceAll("[^0-9]", "-"));
            }
        } catch (ParseException e) {
            e.printStackTrace();
            return false;
        }

        Calendar dobCal = Calendar.getInstance();
        dobCal.setTime(dob);
        int age = Calendar.getInstance().get(Calendar.YEAR) - dobCal.get(Calendar.YEAR);
        if (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) < dobCal.get(Calendar.DAY_OF_YEAR)) {
            age--;
        }
        return age < 19;
    }

    // Send the result back to the calling activity
    private void sendResult(boolean isAdult) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("IS_ADULT", isAdult);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                runOnUiThread(() -> Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show());
                finish();
            }
        }
    }
}
