# Keep source and line information so release crash reports remain actionable.
-keepattributes SourceFile,LineNumberTable

# Avoid leaking local source paths while retaining a stable source-file label.
-renamesourcefileattribute SourceFile
