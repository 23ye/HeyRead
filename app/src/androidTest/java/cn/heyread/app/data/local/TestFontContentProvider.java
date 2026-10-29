package cn.heyread.app.data.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only content provider for synthetic fonts shipped only in the instrumentation APK. */
public final class TestFontContentProvider extends ContentProvider {
  public static final String AUTHORITY = "cn.heyread.app.test.font-fixtures";
  public static final String METHOD_GET_SLOW_STATE = "get_slow_state";
  public static final String METHOD_RELEASE_SLOW_WRITER = "release_slow_writer";
  public static final String KEY_SLOW_BYTES_WRITTEN = "slow_bytes_written";
  public static final String KEY_SLOW_WRITER_CLOSED = "slow_writer_closed";
  public static final String KEY_SLOW_WRITER_DONE = "slow_writer_done";

  private static final Set<String> ALLOWED_NAMES =
      new HashSet<>(
          Arrays.asList("synthetic-a.ttf", "synthetic-b.ttf", "corrupt.ttf", "slow.ttf"));
  private static volatile long slowBytesWritten;
  private static volatile boolean slowWriterObservedClosed;
  private static volatile CountDownLatch slowWriterDone = new CountDownLatch(0);
  private static volatile CountDownLatch slowWriterGate = new CountDownLatch(0);

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    String name = fixtureName(uri);
    MatrixCursor cursor =
        new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
    long bytes;
    try {
      bytes = name.equals("slow.ttf") ? 64L * 1024 * 1024 : fixtureFile(name).length();
    } catch (FileNotFoundException exception) {
      throw new IllegalStateException("Could not prepare font fixture", exception);
    }
    cursor.addRow(new Object[] {name, bytes});
    return cursor;
  }

  @Override
  public String getType(Uri uri) {
    fixtureName(uri);
    return "font/ttf";
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("Fixture provider is read-only");
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    throw new UnsupportedOperationException("Fixture provider is read-only");
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    throw new UnsupportedOperationException("Fixture provider is read-only");
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) {
      throw new IllegalArgumentException("Fixture provider is read-only");
    }
    String name = fixtureName(uri);
    if (name.equals("slow.ttf")) {
      return openSlowStream();
    }
    return ParcelFileDescriptor.open(fixtureFile(name), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (METHOD_GET_SLOW_STATE.equals(method)) {
      Bundle result = new Bundle();
      result.putLong(KEY_SLOW_BYTES_WRITTEN, slowBytesWritten);
      result.putBoolean(KEY_SLOW_WRITER_CLOSED, slowWriterObservedClosed);
      result.putBoolean(KEY_SLOW_WRITER_DONE, slowWriterDone.getCount() == 0);
      return result;
    }
    if (METHOD_RELEASE_SLOW_WRITER.equals(method)) {
      slowWriterGate.countDown();
      return Bundle.EMPTY;
    }
    throw new IllegalArgumentException("Unsupported fixture provider call");
  }

  private String fixtureName(Uri uri) {
    if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) {
      throw new IllegalArgumentException("Unknown font fixture");
    }
    String name = uri.getLastPathSegment();
    if (name == null || !ALLOWED_NAMES.contains(name)) {
      throw new IllegalArgumentException("Unknown font fixture");
    }
    return name;
  }

  private File fixtureFile(String name) throws FileNotFoundException {
    if (name.equals("slow.ttf")) {
      throw new IllegalArgumentException("Slow fixture is streamed");
    }
    File folder = new File(requireContext().getCacheDir(), "font-fixtures");
    if (!folder.exists() && !folder.mkdirs()) {
      throw fileNotFound(new IOException("Could not create fixture cache"));
    }
    File file = new File(folder, name);
    if (!file.exists()) {
      try (FileOutputStream output = new FileOutputStream(file)) {
        if (name.equals("corrupt.ttf")) {
          output.write("not-a-font".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
          try (InputStream input = requireContext().getAssets().open("fonts/" + name)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
              output.write(buffer, 0, count);
            }
          }
        }
      } catch (IOException exception) {
        throw fileNotFound(exception);
      }
    }
    return file;
  }

  private ParcelFileDescriptor openSlowStream() throws FileNotFoundException {
    ParcelFileDescriptor[] pipe;
    try {
      pipe = ParcelFileDescriptor.createPipe();
    } catch (IOException exception) {
      throw fileNotFound(exception);
    }
    CountDownLatch done = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    slowWriterDone = done;
    slowBytesWritten = 0L;
    slowWriterObservedClosed = false;
    slowWriterGate = gate;
    Thread writer =
        new Thread(
            () -> {
              try (ParcelFileDescriptor.AutoCloseOutputStream output =
                  new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(new byte[] {0, 1, 0, 0});
                slowBytesWritten = 4L;
                byte[] chunk = new byte[32 * 1024];
                Arrays.fill(chunk, (byte) 7);
                output.write(chunk);
                slowBytesWritten += chunk.length;
                if (gate.await(5, TimeUnit.SECONDS)) {
                  output.write(chunk);
                  slowBytesWritten += chunk.length;
                }
              } catch (IOException exception) {
                slowWriterObservedClosed = true;
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            },
            "font-fixture-writer");
    writer.setDaemon(true);
    writer.start();
    return pipe[0];
  }

  private FileNotFoundException fileNotFound(IOException cause) {
    FileNotFoundException exception = new FileNotFoundException("Could not open font fixture");
    exception.initCause(cause);
    return exception;
  }
}
