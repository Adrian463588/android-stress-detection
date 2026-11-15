# Keep this file narrow. Add a rule only for a verified reflection/JNI contract.
-renamesourcefileattribute SourceFile

# PDFBox-Android treats JPEG 2000 decoding as an optional integration. The app
# extracts PDF text and uses Android PdfRenderer for OCR; it does not bundle the
# optional JP2Android decoder, so JPX image decoding remains unsupported.
-dontwarn com.gemalto.jp2.JP2Decoder

# Native exports resolve these exact Java class and method names at runtime.
-keep class com.stressdetector.mobile.audio.OpenSmileProsodyExtractor {
    private native java.lang.String[] extractNative(java.lang.String, float[]);
}
-keep class com.stressdetector.mobile.security.AtomicFilePublisher {
    private native int renameNew(java.lang.String, java.lang.String);
}
