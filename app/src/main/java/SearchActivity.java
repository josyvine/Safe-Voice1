package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;


import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchActivity extends Activity implements SearchAdapter.OnItemClickListener, SearchAdapter.OnHeaderCheckedChangeListener {

    private static final String TAG = "SearchActivity";

    private ImageButton closeButton, filterButton, deleteButton;
    private AutoCompleteTextView searchInput;
    private RecyclerView searchResultsGrid;
    private SearchAdapter adapter;
    private GridLayoutManager gridLayoutManager;
    private List<Object> displayList = new ArrayList<>();
    private String currentFilterType = "all";
    private ScaleGestureDetector scaleGestureDetector;
    private int currentSpanCount = 3;
    private static final int MIN_SPAN_COUNT = 1;
    private static final int MAX_SPAN_COUNT = 8;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_OTHER = 5;

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");

    private List<SearchResult> mResultsPendingPermission;
    private Runnable mPendingOperation;


    public static class DateHeader {
        private final String dateString;
        private boolean isChecked;

        public DateHeader(String dateString) {
            this.dateString = dateString;
            this.isChecked = false;
        }

        public String getDateString() {
            return dateString;
        }

        public boolean isChecked() {
            return isChecked;
        }

        public void setChecked(boolean checked) {
            isChecked = checked;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        initializeViews();
        setupListeners();
        setupRecyclerView();
        setupPinchToZoom();
        setupBroadcastReceivers();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button);
        filterButton = findViewById(R.id.filter_button);
        deleteButton = findViewById(R.id.delete_button);
        searchInput = findViewById(R.id.search_input);
        searchResultsGrid = findViewById(R.id.search_results_grid);

        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        filterButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFilterMenu(v);
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFileOperationsDialog();
				}
			});


        searchInput.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
					fetchFolderSuggestions(s.toString());
				}
				@Override public void afterTextChanged(Editable s) {}
			});

        searchInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
				@Override
				public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					String suggestion = (String) parent.getItemAtPosition(position);
					String currentText = searchInput.getText().toString();
					int lastSpaceIndex = currentText.lastIndexOf(' ');
					String newText = (lastSpaceIndex != -1) ? currentText.substring(0, lastSpaceIndex + 1) + suggestion + " " : suggestion + " ";
					searchInput.setText(newText);
					searchInput.setSelection(newText.length());
				}
			});


        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
				@Override
				public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
					executeQuery(searchInput.getText().toString());
					InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
					if (imm != null) {
						imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
					}
					return true;
				}
			});
    }

    private void setupRecyclerView() {
        gridLayoutManager = new GridLayoutManager(this, currentSpanCount);

        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
				@Override
				public int getSpanSize(int position) {
					if (position >= 0 && position < displayList.size()) {
						if (displayList.get(position) instanceof DateHeader) {
							return currentSpanCount;
						}
					}
					return 1;
				}
			});

        searchResultsGrid.setLayoutManager(gridLayoutManager);
        adapter = new SearchAdapter(this, displayList, this, this);
        searchResultsGrid.setAdapter(adapter);
    }

    private void setupPinchToZoom() {
        scaleGestureDetector = new ScaleGestureDetector(this, new PinchZoomListener());
        searchResultsGrid.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					scaleGestureDetector.onTouchEvent(event);
					return false;
				}
			});
    }

    private void executeQuery(final String query) {
        searchExecutor.execute(new Runnable() {
				@Override
				public void run() {
					final QueryParameters params = parseQuery(query);
					List<SearchResult> mediaStoreResults = executeQueryWithMediaStore(params);

					if (!mediaStoreResults.isEmpty()) {
						updateUIWithResults(mediaStoreResults);
					} else {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(SearchActivity.this, "MediaStore found nothing. Starting deep scan...", Toast.LENGTH_SHORT).show();
								}
							});
						List<SearchResult> fileSystemResults = performFallbackFileSearch(params);
						updateUIWithResults(fileSystemResults);
					}
				}
			});
    }

    private void updateUIWithResults(final List<SearchResult> results) {
        final List<Object> groupedList = processAndGroupResults(results);
        runOnUiThread(new Runnable() {
				@Override
				public void run() {
					displayList.clear();
					displayList.addAll(groupedList);
					adapter.updateData(displayList);
					if (results.isEmpty()) {
						Toast.makeText(SearchActivity.this, "No files found.", Toast.LENGTH_SHORT).show();
					}
				}
			});
    }

    private List<Object> processAndGroupResults(List<SearchResult> flatResults) {
        List<Object> groupedList = new ArrayList<>();
        if (flatResults.isEmpty()) {
            return groupedList;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        String currentHeaderDate = "";

        for (SearchResult result : flatResults) {
            String resultDate = sdf.format(new Date(result.getLastModifiedForGrouping()));
            if (!resultDate.equals(currentHeaderDate)) {
                currentHeaderDate = resultDate;
                groupedList.add(new DateHeader(currentHeaderDate));
            }
            groupedList.add(result);
        }

        return groupedList;
    }

    // ===================================================================
    // === THIS METHOD IS UPDATED TO FIX THE CURSORWINDOW CRASH ===
    // It now queries the MediaStore in smaller batches to avoid memory overflow
    // but still returns one single, complete list. Your app logic is unchanged.
    // ===================================================================
    private List<SearchResult> executeQueryWithMediaStore(QueryParameters params) {
        StringBuilder selection = new StringBuilder();
        List<String> selectionArgs = new ArrayList<>();
        Uri queryUri = MediaStore.Files.getContentUri("external");
        addFilterClauses(selection, selectionArgs);

        if (params.startTimeSeconds != -1 && params.endTimeSeconds != -1) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append(MediaStore.Files.FileColumns.DATE_MODIFIED + " >= ? AND " + MediaStore.Files.FileColumns.DATE_MODIFIED + " <= ?");
            selectionArgs.add(String.valueOf(params.startTimeSeconds));
            selectionArgs.add(String.valueOf(params.endTimeSeconds));
        }

        if (params.folderPath != null && !params.folderPath.isEmpty()) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append(MediaStore.Files.FileColumns.DATA + " LIKE ?");
            selectionArgs.add("%" + params.folderPath + "%");
        }

        List<SearchResult> results = new ArrayList<>();
        String[] projection = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA
        };

        final int limit = 2000; // Process records in batches of 2000 to stay within memory limits.
        int offset = 0;
        String baseSortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        while (true) {
            String sortOrder = baseSortOrder + " LIMIT " + limit + " OFFSET " + offset;
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(queryUri, projection, selection.toString(),
                                                   selectionArgs.toArray(new String[0]), sortOrder);

                if (cursor == null || cursor.getCount() == 0) {
                    // No more results, so we are done.
                    break;
                }

                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                int dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    int mediaType = cursor.getInt(mediaTypeColumn);
                    long dateModifiedSeconds = cursor.getLong(dateModifiedColumn);
                    String displayName = cursor.getString(displayNameColumn);
                    String path = cursor.getString(dataColumn);
                    Uri contentUri;
                    if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                        contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    } else if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    } else {
                        contentUri = ContentUris.withAppendedId(queryUri, id);
                    }
                    results.add(new SearchResult(contentUri, id, dateModifiedSeconds * 1000, displayName, path));
                }

                if (cursor.getCount() < limit) {
                    // This was the last page of results, so we can stop.
                    break;
                }

                offset += limit; // Prepare to query the next page of results.

            } finally {
                if (cursor != null) {
                    cursor.close(); // Important to close the cursor for each batch.
                }
            }
        }
        return results;
    }

    private List<SearchResult> performFallbackFileSearch(QueryParameters params) {
        List<SearchResult> results = new ArrayList<>();
        File externalStorage = Environment.getExternalStorageDirectory();

        List<File> rootsToScan = new ArrayList<>();
        rootsToScan.add(new File(externalStorage, "WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Android/media/com.whatsapp/WhatsApp"));
        rootsToScan.add(new File(externalStorage, "Download"));
        rootsToScan.add(new File(externalStorage, "Telegram"));
        rootsToScan.add(new File(externalStorage, "DCIM"));
        rootsToScan.add(new File(externalStorage, "Pictures"));
        rootsToScan.add(new File(externalStorage, "DCIM/Camera"));

        for (File root : rootsToScan) {
            if (root.exists() && root.isDirectory()) {
                scanDirectory(root, params, results);
            }
        }

        Collections.sort(results, new Comparator<SearchResult>() {
				@Override
				public int compare(SearchResult f1, SearchResult f2) {
					return Long.compare(f2.getLastModifiedForGrouping(), f1.getLastModifiedForGrouping());
				}
			});
        return results;
    }

    private void scanDirectory(File directory, QueryParameters params, List<SearchResult> results) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (params.folderPath == null || file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase())) {
                    scanDirectory(file, params, results);
                }
            } else {
                boolean dateMatch = (params.startTimeSeconds == -1) ||
					(file.lastModified() >= params.startTimeSeconds * 1000 && file.lastModified() <= params.endTimeSeconds * 1000);

                boolean folderMatch = (params.folderPath == null) ||
					(file.getAbsolutePath().toLowerCase().contains(params.folderPath.toLowerCase()));

                if (dateMatch && folderMatch) {
                    if (isFileTypeMatch(file.getName())) {
                        results.add(new SearchResult(Uri.fromFile(file), file.lastModified(), file.lastModified(), file.getName(), file.getAbsolutePath()));
                    }
                }
            }
        }
    }

    private void addFilterClauses(StringBuilder selection, List<String> selectionArgs) {
        if ("images".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        } else if ("videos".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?");
            selectionArgs.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        } else if ("documents".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/pdf", "application/msword",
											   "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
											   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint",
											   "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        } else if ("archives".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?)");
            selectionArgs.addAll(Arrays.asList("application/zip", "application/vnd.rar", "application/x-7z-compressed",
											   "application/x-tar", "application/gzip"));
        } else if ("other".equals(currentFilterType)) {
            selection.append(MediaStore.Files.FileColumns.MEDIA_TYPE + " NOT IN (?, ?, ?)");
            selectionArgs.addAll(Arrays.asList(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
											   String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
											   String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)));
        }
    }

    private boolean isFileTypeMatch(String fileName) {
        if (currentFilterType.equals("all")) return true;

        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }

        switch (currentFilterType) {
            case "images":
                return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension);
            case "videos":
                return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(extension);
            case "documents":
                return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt").contains(extension);
            case "archives":
                return Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(extension);
            case "other":
                return !isFileTypeMatch(fileName, "images") && !isFileTypeMatch(fileName, "videos") &&
					!isFileTypeMatch(fileName, "documents") && !isFileTypeMatch(fileName, "archives");
            default:
                return true;
        }
    }

    private boolean isFileTypeMatch(String fileName, String type) {
        String originalFilter = this.currentFilterType;
        this.currentFilterType = type;
        boolean match = isFileTypeMatch(fileName);
        this.currentFilterType = originalFilter;
        return match;
    }

    private QueryParameters parseQuery(String query) {
        QueryParameters params = new QueryParameters();
        String[] originalParts = query.trim().split("\\s+");
        if (originalParts.length == 1 && originalParts[0].isEmpty()) {
            return params;
        }
        boolean[] used = new boolean[originalParts.length];

        for (int i = 0; i <= originalParts.length - 3; i++) {
            if (!used[i] && !used[i+1] && !used[i+2] && originalParts[i + 1].equalsIgnoreCase("days") && originalParts[i + 2].equalsIgnoreCase("ago")) {
                try {
                    int days = Integer.parseInt(originalParts[i]);
                    params.setDateRange(getStartOfDaysAgo(days), getEndOfDaysAgo(days));
                    used[i] = used[i + 1] = used[i + 2] = true;
                } catch (NumberFormatException ignored) {}
            }
        }
        for (int i = 0; i <= originalParts.length - 2; i++) {
            if (!used[i] && !used[i+1] && originalParts[i + 1].equalsIgnoreCase("days")) {
                try {
                    int days = Integer.parseInt(originalParts[i]);
                    params.setDateRange(getStartOfDaysAgo(days - 1), getEndOfToday());
                    used[i] = used[i + 1] = true;
                } catch (NumberFormatException ignored) {}
            }
        }

        for (int i = 0; i < originalParts.length; i++) {
            if (used[i]) continue;
            String partLower = originalParts[i].toLowerCase();
            if (partLower.equals("today")) {
                params.setDateRange(getStartOfToday(), getEndOfToday());
                used[i] = true;
            } else if (partLower.equals("yesterday")) {
                params.setDateRange(getStartOfYesterday(), getEndOfYesterday());
                used[i] = true;
            } else if (partLower.equals("phone") || partLower.equals("sdcard")) {
                used[i] = true;
            }
        }

        StringBuilder folderBuilder = new StringBuilder();
        for (int i = 0; i < originalParts.length; i++) {
            if (!used[i]) {
                if (folderBuilder.length() > 0) folderBuilder.append(" ");
                folderBuilder.append(originalParts[i]);
            }
        }
        String finalFolderPath = folderBuilder.toString().trim();
        if (!finalFolderPath.isEmpty()) {
            params.folderPath = finalFolderPath;
        }
        return params;
    }

    private long getStartOfToday() { Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); return c.getTimeInMillis() / 1000; }
    private long getEndOfToday() { Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); return c.getTimeInMillis() / 1000; }
    private long getStartOfYesterday() { return getStartOfDaysAgo(1); }
    private long getEndOfYesterday() { return getEndOfDaysAgo(1); }
    private long getStartOfDaysAgo(int days) { Calendar c = Calendar.getInstance(); c.add(Calendar.DATE, -days); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); return c.getTimeInMillis() / 1000; }
    private long getEndOfDaysAgo(int days) { Calendar c = Calendar.getInstance(); c.add(Calendar.DATE, -days); c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); return c.getTimeInMillis() / 1000; }

    private void fetchFolderSuggestions(final String constraint) {
        new Thread(new Runnable() {
				@Override
				public void run() {
					String lastWord = constraint;
					int lastSpaceIndex = constraint.lastIndexOf(' ');
					if (lastSpaceIndex != -1) {
						lastWord = constraint.substring(lastSpaceIndex + 1);
					}
					if (lastWord.isEmpty()) {
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									if (searchInput != null) searchInput.dismissDropDown();
								}
							});
						return;
					}
					Set<String> folderSet = new HashSet<>();
					Uri uri = MediaStore.Files.getContentUri("external");
					String[] projection = {MediaStore.Files.FileColumns.DATA};
					Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
					if (cursor != null) {
						int dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
						while (cursor.moveToNext()) {
							String path = cursor.getString(dataColumn);
							if (path != null) {
								File parentFile = new File(path).getParentFile();
								if (parentFile != null && parentFile.getName().toLowerCase().startsWith(lastWord.toLowerCase())) {
									folderSet.add(parentFile.getName());
								}
							}
						}
						cursor.close();
					}
					final List<String> suggestions = new ArrayList<>(folderSet);
					runOnUiThread(new Runnable() {
							@Override
							public void run() {
								if (searchInput == null) return;
								ArrayAdapter<String> suggestionAdapter = new ArrayAdapter<>(SearchActivity.this, android.R.layout.simple_dropdown_item_1line, suggestions);
								searchInput.setAdapter(suggestionAdapter);
								if (!suggestions.isEmpty() && searchInput.isFocused()) {
									searchInput.showDropDown();
								}
							}
						});
				}
			}).start();
    }

    private void initiateDeletionProcess() {
        final List<SearchResult> selectedResults = new ArrayList<>();
        for (Object item : displayList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (!result.isExcluded()) {
                    selectedResults.add(result);
                }
            }
        }

        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requiresSdCardPermission = false;
        for (SearchResult result : selectedResults) {
            if (result.getPath() != null) {
                File file = new File(result.getPath());
                if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                    requiresSdCardPermission = true;
                    break;
                }
            }
        }

        if (requiresSdCardPermission) {
            mResultsPendingPermission = selectedResults;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    confirmAndDelete(selectedResults);
                }
            };
            promptForSdCardPermission();
        } else {
            confirmAndDelete(selectedResults);
        }
    }

    private void confirmAndDelete(final List<SearchResult> filesToConfirm) {
        final Set<SearchResult> masterDeleteSet = new HashSet<>();
        for (SearchResult selectedResult : filesToConfirm) {
            masterDeleteSet.addAll(findSiblingFiles(selectedResult));
        }

        final List<SearchResult> toDelete = new ArrayList<>(masterDeleteSet);
        String dialogMessage;

        if (toDelete.size() > filesToConfirm.size()) {
            int siblingCount = toDelete.size() - filesToConfirm.size();
            dialogMessage = "You selected <b>" + filesToConfirm.size() + "</b> file(s), but we found <b>" + siblingCount
                + "</b> other related version(s).<br/><br/>Choose an action for all <b>"
                + toDelete.size() + "</b> related files.";
        } else {
            dialogMessage = "Choose an action for the " + toDelete.size() + " selected file(s).";
        }

        new AlertDialog.Builder(this).setTitle("Confirm Action")
			.setMessage(Html.fromHtml(dialogMessage))
			.setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					performDelete(toDelete);
				}
			})
            .setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    moveToRecycleBin(toDelete);
                }
            })
            .setNegativeButton("Hide Files", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    hideFiles(toDelete);
                }
            })
			.show();
    }

    private void hideFiles(List<SearchResult> resultsToHide) {
        ArrayList<File> filesToHide = new ArrayList<>();
        for (SearchResult result : resultsToHide) {
            if (result.getPath() != null) {
                filesToHide.add(new File(result.getPath()));
            }
        }

        if (filesToHide.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths to hide.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, FileHiderActivity.class);
        intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
        startActivity(intent);
    }


    private void moveToRecycleBin(List<SearchResult> resultsToMove) {
        new MoveToRecycleTask(resultsToMove).execute();
    }

    private List<SearchResult> findSiblingFiles(SearchResult originalResult) {
        List<SearchResult> siblings = new ArrayList<>();
        siblings.add(originalResult);

        if (originalResult.getPath() == null) {
            return siblings;
        }

        File originalFile = new File(originalResult.getPath());
        String fileName = originalFile.getName();
        Matcher matcher = FILE_BASE_NAME_PATTERN.matcher(fileName);

        if (matcher.find()) {
            String baseName = matcher.group(0);
            File parentDir = originalFile.getParentFile();

            if (parentDir != null && parentDir.isDirectory()) {
                for (Object item : displayList) {
                    if (item instanceof SearchResult) {
                        SearchResult potentialSibling = (SearchResult) item;
                        if (potentialSibling.getPath() != null) {
                            File potentialFile = new File(potentialSibling.getPath());
                            if (potentialFile.getParent() != null && potentialFile.getParent().equals(parentDir.getAbsolutePath()) &&
                                potentialFile.getName().startsWith(baseName) &&
                                !potentialFile.getAbsolutePath().equals(originalFile.getAbsolutePath())) {
                                siblings.add(potentialSibling);
                            }
                        }
                    }
                }
            }
        }
        return siblings;
    }


    private void performDelete(final List<SearchResult> toDelete) {
        ArrayList<String> filePathsToDelete = new ArrayList<>();
        for (SearchResult result : toDelete) {
            if (result.getPath() != null) {
                filePathsToDelete.add(result.getPath());
            }
        }

        if (filePathsToDelete.isEmpty()) {
            Toast.makeText(this, "Could not resolve file paths for deletion.", Toast.LENGTH_SHORT).show();
            return;
        }

        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Starting deletion...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, filePathsToDelete);
        ContextCompat.startForegroundService(this, intent);
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
            .setTitle("SD Card Permission Needed")
            .setMessage("To delete files on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card (e.g., 'Galaxy SD Card') on the next screen and tap 'Allow'.")
            .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    StorageUtils.requestSdCardPermission(SearchActivity.this);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
																	  Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    StorageUtils.saveSdCardUri(this, treeUri);
                    Toast.makeText(this, "SD card access granted.", Toast.LENGTH_SHORT).show();

                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    } else if (mResultsPendingPermission != null && !mResultsPendingPermission.isEmpty()) {
                        confirmAndDelete(mResultsPendingPermission);
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mResultsPendingPermission = null;
            mPendingOperation = null;
        }
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.filter_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					int itemId = item.getItemId();
					if (itemId == R.id.filter_all) currentFilterType = "all";
					else if (itemId == R.id.filter_images) currentFilterType = "images";
					else if (itemId == R.id.filter_videos) currentFilterType = "videos";
					else if (itemId == R.id.filter_documents) currentFilterType = "documents";
					else if (itemId == R.id.filter_archives) currentFilterType = "archives";
					else if (itemId == R.id.filter_other) currentFilterType = "other";
					executeQuery(searchInput.getText().toString());
					return true;
				}
			});
        popup.show();
    }

    @Override
    public void onItemClick(SearchResult item) {
        item.setExcluded(!item.isExcluded());
        updateHeaderStateForItem(item);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onItemLongClick(final SearchResult item) {
        final CharSequence[] options = {"Open", "Details", "Compress"};
        new AlertDialog.Builder(this)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        openFileViewer(item);
                    } else if (which == 1) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        showDetailsDialog(files);
                    } else if (which == 2) {
                        List<File> files = getFilesFromResults(Collections.singletonList(item));
                        if (!files.isEmpty() && files.get(0).getParentFile() != null) {
                            ArchiveUtils.startCompression(SearchActivity.this, files, files.get(0).getParentFile());
                            Toast.makeText(SearchActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            })
            .show();
    }


    @Override
    public void onHeaderCheckedChanged(DateHeader header, boolean isChecked) {
        header.setChecked(isChecked);
        int headerIndex = displayList.indexOf(header);
        if (headerIndex == -1) return;

        for (int i = headerIndex + 1; i < displayList.size(); i++) {
            Object currentItem = displayList.get(i);
            if (currentItem instanceof SearchResult) {
                ((SearchResult) currentItem).setExcluded(!isChecked);
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void updateHeaderStateForItem(SearchResult item) {
        int itemIndex = displayList.indexOf(item);
        if (itemIndex == -1) return;

        DateHeader parentHeader = null;
        for (int i = itemIndex - 1; i >= 0; i--) {
            if (displayList.get(i) instanceof DateHeader) {
                parentHeader = (DateHeader) displayList.get(i);
                break;
            }
        }
        if (parentHeader == null) return;

        boolean allIncluded = true;
        int headerIndex = displayList.indexOf(parentHeader);
        for (int i = headerIndex + 1; i < displayList.size(); i++) {
            Object currentItem = displayList.get(i);
            if (currentItem instanceof SearchResult) {
                if (((SearchResult) currentItem).isExcluded()) {
                    allIncluded = false;
                    break;
                }
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        parentHeader.setChecked(allIncluded);
    }

    public static class SearchResult {
        private final Uri uri;
        private final long mediaStoreId;
        private final long lastModifiedForGrouping;
        private final String displayName;
        private final String path;
        private boolean isExcluded;

        public SearchResult(Uri uri, long mediaStoreId, long lastModifiedMillis, String displayName, String path) {
            this.uri = uri;
            this.mediaStoreId = mediaStoreId;
            this.lastModifiedForGrouping = lastModifiedMillis;
            this.displayName = displayName;
            this.path = path;
            this.isExcluded = true;
        }
        public Uri getUri() { return uri; }
        public long getLastModified() { return mediaStoreId; }
        public long getLastModifiedForGrouping() { return lastModifiedForGrouping; }
        public String getDisplayName() { return displayName; }
        public String getPath() { return path; }
        public boolean isExcluded() { return isExcluded; }
        public void setExcluded(boolean excluded) { isExcluded = excluded; }
    }

    private static class QueryParameters {
        String folderPath;
        long startTimeSeconds = -1;
        long endTimeSeconds = -1;
        void setDateRange(long start, long end) { this.startTimeSeconds = start; this.endTimeSeconds = end; }
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            int previousSpanCount = currentSpanCount;
            if (scaleFactor > 1.05f) currentSpanCount = Math.max(MIN_SPAN_COUNT, currentSpanCount - 1);
            else if (scaleFactor < 0.95f) currentSpanCount = Math.min(MAX_SPAN_COUNT, currentSpanCount + 1);

            if (previousSpanCount != currentSpanCount) {
                gridLayoutManager.setSpanCount(currentSpanCount);
                adapter.notifyDataSetChanged();
            }
            return true;
        }
    }

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");

        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private void openFileViewer(final SearchResult item) {
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                File file = new File(item.getPath());
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) {
						return null;
                    }

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(SearchActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(SearchActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(SearchActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(SearchActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(SearchActivity.this, TextViewerActivity.class);
                    }
                    intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
                }
                return intent;
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(SearchActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        File parentDir = currentFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            siblingFiles.add(currentFile.getAbsolutePath());
            return siblingFiles;
        }

        for (Object item : displayList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (result.getPath() != null) {
                    File file = new File(result.getPath());
                    if (file.getParentFile() != null && file.getParentFile().equals(parentDir)) {
                        if (getFileCategory(file.getName()) == category) {
                            siblingFiles.add(file.getAbsolutePath());
                        }
                    }
                }
            }
        }

        Collections.sort(siblingFiles);
        return siblingFiles;
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(SearchActivity.this, "Deletion complete. " + deletedCount + " files removed.", Toast.LENGTH_LONG).show();

                deletionProgressLayout.setVisibility(View.GONE);
                executeQuery(searchInput.getText().toString());
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    executeQuery(searchInput.getText().toString());
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(compressionBroadcastReceiver, new IntentFilter(CompressionService.ACTION_COMPRESSION_COMPLETE));
    }

    @Override
    protected void onDestroy() {
        if (deleteCompletionReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(deleteCompletionReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }

    private void showFileOperationsDialog() {
        final List<SearchResult> selectedResults = new ArrayList<>();
        for (Object item : displayList) {
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (!result.isExcluded()) {
                    selectedResults.add(result);
                }
            }
        }

        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<File> selectedFiles = getFilesFromResults(selectedResults);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_operations, null);
        builder.setView(dialogView);
        final AlertDialog dialog = builder.create();

        Button detailsButton = dialogView.findViewById(R.id.button_details);
        Button compressButton = dialogView.findViewById(R.id.button_compress);
        Button copyButton = dialogView.findViewById(R.id.button_copy);
        Button moveButton = dialogView.findViewById(R.id.button_move);
        Button hideButton = dialogView.findViewById(R.id.button_hide);
        Button deleteButton = dialogView.findViewById(R.id.button_delete_permanently);
        Button recycleButton = dialogView.findViewById(R.id.button_move_to_recycle);

        copyButton.setVisibility(View.GONE);
        moveButton.setVisibility(View.GONE);

        detailsButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showDetailsDialog(selectedFiles);
					dialog.dismiss();
				}
			});

        compressButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (!selectedFiles.isEmpty() && selectedFiles.get(0).getParentFile() != null) {
						ArchiveUtils.startCompression(SearchActivity.this, selectedFiles, selectedFiles.get(0).getParentFile());
						Toast.makeText(SearchActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(SearchActivity.this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
					}
					dialog.dismiss();
				}
			});

        hideButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					hideFiles(selectedResults);
					dialog.dismiss();
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					initiateDeletionProcess();
					dialog.dismiss();
				}
			});

        recycleButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					moveToRecycleBin(selectedResults);
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private List<File> getFilesFromResults(List<SearchResult> results) {
        List<File> files = new ArrayList<>();
        for (SearchResult result : results) {
            if (result.getPath() != null) {
                files.add(new File(result.getPath()));
            }
        }
        return files;
    }

    private void showDetailsDialog(final List<File> files) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        if (files.size() == 1) {
            File file = files.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(file.getName()).append("\n");
            sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
            sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
            sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
            basicDetailsText.setText(sb.toString());
        } else {
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            basicDetailsText.setText("Items selected: " + files.size() + "\nTotal size: " + Formatter.formatFileSize(this, totalSize));
        }

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					analyzer.analyze(files);
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
					clipboard.setPrimaryClip(clip);
					Toast.makeText(SearchActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
				}
			});

        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private class MoveToRecycleTask extends AsyncTask<Void, Void, List<SearchResult>> {
        private AlertDialog progressDialog;
        private List<SearchResult> resultsToMove;
        private Context context;

        public MoveToRecycleTask(List<SearchResult> resultsToMove) {
            this.resultsToMove = resultsToMove;
            this.context = SearchActivity.this;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
            TextView progressText = dialogView.findViewById(R.id.progress_text);
            progressText.setText("Moving files to Recycle Bin...");
            builder.setView(dialogView);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected List<SearchResult> doInBackground(Void... voids) {
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists()) {
                if (!recycleBinDir.mkdir()) {
                    return new ArrayList<>();
                }
            }

            List<SearchResult> movedResults = new ArrayList<>();
            for (SearchResult result : resultsToMove) {
                if (result.getPath() == null) continue;

                File sourceFile = new File(result.getPath());
                if (sourceFile.exists()) {
                    File destFile = new File(recycleBinDir, sourceFile.getName());

                    if (destFile.exists()) {
                        String name = sourceFile.getName();
                        String extension = "";
                        int dotIndex = name.lastIndexOf(".");
                        if (dotIndex > 0) {
                            extension = name.substring(dotIndex);
                            name = name.substring(0, dotIndex);
                        }
                        destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
                    }

                    boolean moveSuccess = false;
                    boolean isSourceOnSd = StorageUtils.isFileOnSdCard(context, sourceFile);

                    if (isSourceOnSd) {
                        if (copyFile(sourceFile, destFile)) {
                            if (StorageUtils.deleteFile(context, sourceFile)) {
                                moveSuccess = true;
                            } else {
                                destFile.delete();
                            }
                        }
                    } else {
                        if (sourceFile.renameTo(destFile)) {
                            moveSuccess = true;
                        }
                    }

                    if (moveSuccess) {
                        movedResults.add(result);
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(sourceFile)));
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
                    } else {
                        Log.w(TAG, "Failed to move file to recycle bin: " + sourceFile.getAbsolutePath());
                    }
                }
            }
            return movedResults;
        }

        @Override
        protected void onPostExecute(List<SearchResult> movedResults) {
            progressDialog.dismiss();

            if (movedResults.isEmpty() && !resultsToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move some or all files.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, movedResults.size() + " file(s) moved to Recycle Bin.", Toast.LENGTH_LONG).show();
            }

            if (!movedResults.isEmpty()) {
                displayList.removeAll(movedResults);

                List<Object> itemsToRemove = new ArrayList<>();
                for (int i = 0; i < displayList.size(); i++) {
                    Object currentItem = displayList.get(i);
                    if (currentItem instanceof DateHeader) {
                        if (i + 1 >= displayList.size() || displayList.get(i + 1) instanceof DateHeader) {
                            itemsToRemove.add(currentItem);
                        }
                    }
                }
                displayList.removeAll(itemsToRemove);

                adapter.notifyDataSetChanged();
            }
        }

        private boolean copyFile(File source, File destination) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(source);
                out = new FileOutputStream(destination);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Standard file copy failed, attempting with StorageUtils", e);
                return StorageUtils.copyFile(context, source, destination);
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}