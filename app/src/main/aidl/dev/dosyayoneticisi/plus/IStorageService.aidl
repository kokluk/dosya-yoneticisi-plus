package dev.dosyayoneticisi.plus;
interface IStorageService {
    void destroy() = 16777114;
    String[] children(String path) = 0;
    String details(String path) = 1;
    void chmod(String path, int mode) = 2;
    android.os.ParcelFileDescriptor openRead(String path, long limit) = 3;
}

