package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StorageBrowserActivity extends Activity implements StorageBrowserAdapter.OnItemClickListener, StorageBrowserAdapter.OnHeaderCheckedChangeListener {

    private static final String TAG = "StorageBrowserActivity";

    private TextView titleTextView, pathTextView;
    private ImageButton backButton, sortButton, deleteButton;
    private EditText searchInput;
    private RecyclerView fileGrid;
    private RelativeLayout loadingView;
    private RelativeLayout operationProgressLayout;
    private ProgressBar operationProgressBar;
    private TextView operationProgressText;

    private RelativeLayout pasteControlsLayout;
    private Button pasteButton, createFolderButton, cancelPasteButton;

    private StorageBrowserAdapter adapter;
    private List<Object> masterList = new ArrayList<>();
    private String currentPath;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver operationBroadcastReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;
    private GridLayoutManager gridLayoutManager;

    private static final int SORT_BY_NAME = 1;
    private static final int SORT_BY_DATE = 2;
    private static final int SORT_BY_SIZE = 3;
    private static final int SORT_BY_TYPE = 4;
    private int currentSortOrder = SORT_BY_DATE;

    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");
    private List<File> mFilesPendingPermission;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_OTHER = 5;

    private Runnable mPendingOperation;
    private File mFilePendingPermissionForExtraction;


    public static class DateHeader {
        private final String dateString;
        private boolean isChecked;

        public DateHeader(String dateString) {
            this.dateString = dateString;
            this.isChecked = false;
        }

        public String getDateString() { return dateString; }
        public boolean isChecked() { return isChecked; }
        public void setChecked(boolean checked) { isChecked = checked; }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_browser);

        initializeViews();

        Intent intent = getIntent();
        currentPath = intent.getStringExtra(DashboardActivity.EXTRA_STORAGE_PATH);
        String storageName = intent.getStringExtra(DashboardActivity.EXTRA_STORAGE_NAME);

        if (currentPath == null || storageName == null) {
            Toast.makeText(this, "Error: Storage path not provided.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        titleTextView.setText(storageName);
        pathTextView.setText(currentPath);

        setupRecyclerView();
        setupListeners();
        setupBroadcastReceivers();

        new ScanFilesTask().execute(new File(currentPath));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFooterUI();
    }

    private void initializeViews() {
        titleTextView = findViewById(R.id.title_storage_browser);
        pathTextView = findViewById(R.id.path_storage_browser);
        backButton = findViewById(R.id.back_button_storage_browser);
        sortButton = findViewById(R.id.sort_button_storage_browser);
        deleteButton = findViewById(R.id.delete_button_storage_browser);
        searchInput = findViewById(R.id.search_input_storage_browser);
        fileGrid = findViewById(R.id.file_grid_storage_browser);
        loadingView = findViewById(R.id.loading_view_browser);
        operationProgressLayout = findViewById(R.id.operation_progress_layout);
        operationProgressBar = findViewById(R.id.operation_progress_bar);
        operationProgressText = findViewById(R.id.operation_progress_text);

        pasteControlsLayout = findViewById(R.id.paste_controls_layout);
        pasteButton = findViewById(R.id.paste_button);
        createFolderButton = findViewById(R.id.create_folder_button);
        cancelPasteButton = findViewById(R.id.cancel_paste_button);
    }

    private void setupRecyclerView() {
        adapter = new StorageBrowserAdapter(this, new ArrayList<Object>(), this, this);
        gridLayoutManager = new GridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
				@Override
				public int getSpanSize(int position) {
					if (position >= 0 && position < adapter.getFilteredItems().size()) {
						if (adapter.getFilteredItems().get(position) instanceof DateHeader) {
							return gridLayoutManager.getSpanCount();
						}
					}
					return 1;
				}
			});

        fileGrid.setLayoutManager(gridLayoutManager);
        fileGrid.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					File file = new File(currentPath);
					File parent = file.getParentFile();
					if (parent != null) {
						currentPath = parent.getAbsolutePath();
						pathTextView.setText(currentPath);
						new ScanFilesTask().execute(parent);
					} else {
						finish();
					}
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (com.hfm.app.ClipboardManager.getInstance().hasItems()) {
						updateFooterUI();
					} else {
						showFileOperationsDialog();
					}
				}
			});

        sortButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showSortMenu(v);
				}
			});

        searchInput.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
					if (adapter != null) {
						adapter.getFilter().filter(s);
					}
				}
				@Override public void afterTextChanged(Editable s) {}
			});

        pasteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					performPaste();
				}
			});

        createFolderButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showCreateFolderDialog();
				}
			});

        cancelPasteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					com.hfm.app.ClipboardManager.getInstance().clear();
					updateFooterUI();
					Toast.makeText(StorageBrowserActivity.this, "Operation cancelled.", Toast.LENGTH_SHORT).show();
				}
			});
    }

    @Override
    public void onItemClick(int position, Object item) {
        if (item instanceof StorageBrowserAdapter.FileItem) {
            StorageBrowserAdapter.FileItem fileItem = (StorageBrowserAdapter.FileItem) item;
            File file = fileItem.getFile();
            if (file.isDirectory()) {
                currentPath = file.getAbsolutePath();
                pathTextView.setText(currentPath);
                new ScanFilesTask().execute(file);
            } else {
                fileItem.setSelected(!fileItem.isSelected());
                adapter.notifyItemChanged(position);
                updateHeaderStateForItem(fileItem);
            }
        }
    }

    @Override
    public void onItemLongClick(int position, Object item) {
        if (item instanceof StorageBrowserAdapter.FileItem) {
            final File selectedFile = ((StorageBrowserAdapter.FileItem) item).getFile();
            if (selectedFile.isDirectory()) {
                showFolderOperationsDialog(selectedFile);
            } else {
                String fileName = selectedFile.getName().toLowerCase();
                if (fileName.endsWith(".zip") || fileName.endsWith(".rar")) {
                    showArchiveOperationsDialog(selectedFile);
                } else {
                    openFileViewer(selectedFile);
                }
            }
        }
    }

    @Override
    public void onSelectionChanged() {
        // Not used, handled by header/item clicks
    }

    @Override
    public void onHeaderCheckedChanged(DateHeader header, boolean isChecked) {
        header.setChecked(isChecked);
        int headerIndex = masterList.indexOf(header);
        if (headerIndex == -1) return;

        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof StorageBrowserAdapter.FileItem) {
                ((StorageBrowserAdapter.FileItem) currentItem).setSelected(isChecked);
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void updateHeaderStateForItem(StorageBrowserAdapter.FileItem item) {
        int itemIndex = -1;
        for (int i = 0; i < masterList.size(); i++) {
            if (masterList.get(i) == item) {
                itemIndex = i;
                break;
            }
        }
        if (itemIndex == -1) return;

        DateHeader parentHeader = null;
        int headerIndex = -1;
        for (int i = itemIndex - 1; i >= 0; i--) {
            if (masterList.get(i) instanceof DateHeader) {
                parentHeader = (DateHeader) masterList.get(i);
                headerIndex = i;
                break;
            }
        }
        if (parentHeader == null) return;

        boolean allChildrenSelected = true;
        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof StorageBrowserAdapter.FileItem) {
                if(!((StorageBrowserAdapter.FileItem) currentItem).getFile().isDirectory()){
                    if (!((StorageBrowserAdapter.FileItem) currentItem).isSelected()) {
                        allChildrenSelected = false;
                        break;
                    }
                }
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        parentHeader.setChecked(allChildrenSelected);
        adapter.notifyItemChanged(headerIndex);
    }

    private void showFileOperationsDialog() {
        final List<File> selectedFiles = getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

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
					ArchiveUtils.startCompression(StorageBrowserActivity.this, selectedFiles, new File(currentPath));
					Toast.makeText(StorageBrowserActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
					dialog.dismiss();
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.COPY);
					Toast.makeText(StorageBrowserActivity.this, selectedFiles.size() + " item(s) ready to copy.", Toast.LENGTH_SHORT).show();
					updateFooterUI();
					dialog.dismiss();
				}
			});

        moveButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.MOVE);
					Toast.makeText(StorageBrowserActivity.this, selectedFiles.size() + " item(s) ready to move.", Toast.LENGTH_SHORT).show();
					updateFooterUI();
					dialog.dismiss();
				}
			});

        hideButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Intent intent = new Intent(StorageBrowserActivity.this, FileHiderActivity.class);
					intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) selectedFiles);
					startActivity(intent);
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
					moveToRecycleBin(selectedFiles);
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private void showFolderOperationsDialog(final File folder) {
        final CharSequence[] options = {"Details", "Compress", "Copy", "Move", "Hide", "Move to Recycle Bin", "Delete Permanently"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Folder Operation: " + folder.getName());
        builder.setItems(options, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					List<File> folderList = new ArrayList<>();
					folderList.add(folder);
					switch (which) {
                        case 0: // Details
                            showDetailsDialog(folderList);
                            break;
                        case 1: // Compress
                            ArchiveUtils.startCompression(StorageBrowserActivity.this, folderList, new File(currentPath));
                            Toast.makeText(StorageBrowserActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                            break;
						case 2: // Copy
							com.hfm.app.ClipboardManager.getInstance().setItems(folderList, com.hfm.app.ClipboardManager.Operation.COPY);
							Toast.makeText(StorageBrowserActivity.this, "Folder '" + folder.getName() + "' ready to copy.", Toast.LENGTH_SHORT).show();
							updateFooterUI();
							break;
						case 3: // Move
							com.hfm.app.ClipboardManager.getInstance().setItems(folderList, com.hfm.app.ClipboardManager.Operation.MOVE);
							Toast.makeText(StorageBrowserActivity.this, "Folder '" + folder.getName() + "' ready to move.", Toast.LENGTH_SHORT).show();
							updateFooterUI();
							break;
                        case 4: // Hide
                            new GatherFilesForHidingTask().execute(folder);
                            break;
						case 5: // Move to Recycle Bin
							moveToRecycleBin(folderList);
							break;
						case 6: // Delete Permanently
							initiateFolderDeletionProcess(folder);
							break;
					}
				}
			});
        builder.show();
    }

    private void showArchiveOperationsDialog(final File archiveFile) {
        final CharSequence[] options = {"Details", "Extract Here"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(archiveFile.getName());
        builder.setItems(options, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) { // Details
                        List<File> fileList = new ArrayList<>();
                        fileList.add(archiveFile);
                        showDetailsDialog(fileList);
                    } else if (which == 1) { // Extract Here
						final File destination = new File(currentPath);
						if (StorageUtils.isFileOnSdCard(StorageBrowserActivity.this, destination) && !StorageUtils.hasSdCardPermission(StorageBrowserActivity.this)) {
							mFilePendingPermissionForExtraction = archiveFile;
							mPendingOperation = new Runnable() {
								@Override
								public void run() {
									ArchiveUtils.extractArchive(StorageBrowserActivity.this, archiveFile, destination);
								}
							};
							promptForSdCardPermission();
						} else {
							ArchiveUtils.extractArchive(StorageBrowserActivity.this, archiveFile, destination);
						}
					}
				}
			});
        builder.show();
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

        // Populate basic details
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
					Toast.makeText(StorageBrowserActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
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

    private void initiateFolderDeletionProcess(final File folder) {
        new AsyncTask<Void, Void, ArrayList<String>>() {
            AlertDialog progressDialog;

            @Override
            protected void onPreExecute() {
                AlertDialog.Builder builder = new AlertDialog.Builder(StorageBrowserActivity.this);
                builder.setView(R.layout.dialog_progress_simple);
                builder.setCancelable(false);
                progressDialog = builder.create();
                progressDialog.show();
            }

            @Override
            protected ArrayList<String> doInBackground(Void... voids) {
                return getAllFilePathsRecursive(folder);
            }

            @Override
            protected void onPostExecute(ArrayList<String> allFilePaths) {
                progressDialog.dismiss();
                if (allFilePaths.isEmpty()) {
                    Toast.makeText(StorageBrowserActivity.this, "Folder is empty.", Toast.LENGTH_SHORT).show();
                    return;
                }
                performDeletion(allFilePaths);
            }
        }.execute();
    }


    private void initiateDeletionProcess() {
        final List<File> initiallySelectedFiles = getSelectedFiles();

        if (initiallySelectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requiresSdCardPermission = false;
        for (File file : initiallySelectedFiles) {
            if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                requiresSdCardPermission = true;
                break;
            }
        }

        if (requiresSdCardPermission) {
            mFilesPendingPermission = initiallySelectedFiles;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    showDeleteConfirmationDialog(initiallySelectedFiles);
                }
            };
            promptForSdCardPermission();
        } else {
            showDeleteConfirmationDialog(initiallySelectedFiles);
        }
    }

    private void showDeleteConfirmationDialog(final List<File> filesToConfirm) {
        final Set<File> masterDeleteSet = new HashSet<>();
        for (File selectedFile : filesToConfirm) {
            masterDeleteSet.addAll(findSiblingFiles(selectedFile));
        }

        final List<File> filesToDelete = new ArrayList<>(masterDeleteSet);
        String dialogMessage;

        if (filesToDelete.size() > filesToConfirm.size()) {
            int siblingCount = filesToDelete.size() - filesToConfirm.size();
            dialogMessage = "You selected <b>" + filesToConfirm.size() + "</b> file(s), but we found <b>" + siblingCount
				+ "</b> other related version(s).<br/><br/>Are you sure you want to permanently delete all <b>"
				+ filesToDelete.size() + "</b> related files? This action cannot be undone.";
        } else {
            dialogMessage = "Are you sure you want to permanently delete " + filesToDelete.size() + " file(s)? This action cannot be undone.";
        }

        new AlertDialog.Builder(this)
			.setTitle("Confirm Deletion")
			.setMessage(Html.fromHtml(dialogMessage))
			.setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
                    ArrayList<String> pathsToDelete = new ArrayList<>();
                    for(File f : filesToDelete) {
                        pathsToDelete.add(f.getAbsolutePath());
                    }
					performDeletion(pathsToDelete);
				}
			})
			.setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					moveToRecycleBin(filesToDelete);
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
    }

    private void moveToRecycleBin(List<File> filesToMove) {
        new MoveToRecycleTask(filesToMove).execute();
    }

    private void performDeletion(List<String> filePathsToDelete) {
        if (filePathsToDelete.isEmpty()) {
            Toast.makeText(this, "No files to delete.", Toast.LENGTH_SHORT).show();
            return;
        }

        operationProgressLayout.setVisibility(View.VISIBLE);
        operationProgressBar.setIndeterminate(true);
        operationProgressText.setText("Starting deletion...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, new ArrayList<String>(filePathsToDelete));
        ContextCompat.startForegroundService(this, intent);
    }

    private List<File> findSiblingFiles(File originalFile) {
        List<File> siblings = new ArrayList<>();
        siblings.add(originalFile);
        String fileName = originalFile.getName();
        Matcher matcher = FILE_BASE_NAME_PATTERN.matcher(fileName);
        if (matcher.find()) {
            String baseName = matcher.group(0);
            File parentDir = originalFile.getParentFile();
            if (parentDir != null && parentDir.isDirectory()) {
                File[] filesInDir = parentDir.listFiles();
                if (filesInDir != null) {
                    for (File potentialSibling : filesInDir) {
                        if (potentialSibling.isFile() && potentialSibling.getName().startsWith(baseName) && !potentialSibling.getAbsolutePath().equals(originalFile.getAbsolutePath())) {
                            siblings.add(potentialSibling);
                        }
                    }
                }
            }
        }
        return siblings;
    }


    private class ScanFilesTask extends AsyncTask<File, Void, List<File>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            fileGrid.setVisibility(View.GONE);
        }

        @Override
        protected List<File> doInBackground(File... dirs) {
            File[] files = dirs[0].listFiles();
            if (files != null) {
                return new ArrayList<>(Arrays.asList(files));
            }
            return new ArrayList<>();
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            sortAndGroupFiles(result);
            adapter.updateMasterList(masterList);
            loadingView.setVisibility(View.GONE);
            fileGrid.setVisibility(View.VISIBLE);
        }
    }

    private void showSortMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.sort_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					int itemId = item.getItemId();
					if (itemId == R.id.sort_by_name) {
						currentSortOrder = SORT_BY_NAME;
					} else if (itemId == R.id.sort_by_date) {
						currentSortOrder = SORT_BY_DATE;
					} else if (itemId == R.id.sort_by_size) {
						currentSortOrder = SORT_BY_SIZE;
					} else if (itemId == R.id.sort_by_type) {
						currentSortOrder = SORT_BY_TYPE;
					}
					sortAndGroupFiles(getCurrentFiles());
					adapter.updateMasterList(masterList);
					return true;
				}
			});
        popup.show();
    }

    private List<File> getCurrentFiles() {
        List<File> currentFiles = new ArrayList<>();
        for (Object item : masterList) {
            if (item instanceof StorageBrowserAdapter.FileItem) {
                currentFiles.add(((StorageBrowserAdapter.FileItem) item).getFile());
            }
        }
        return currentFiles;
    }

    private void sortAndGroupFiles(List<File> files) {
        Collections.sort(files, new Comparator<File>() {
				@Override
				public int compare(File f1, File f2) {
					if (f1.isDirectory() && !f2.isDirectory()) return -1;
					if (!f1.isDirectory() && f2.isDirectory()) return 1;

					switch (currentSortOrder) {
						case SORT_BY_NAME:
							return f1.getName().compareToIgnoreCase(f2.getName());
						case SORT_BY_SIZE:
							return Long.compare(f2.length(), f1.length());
						case SORT_BY_TYPE:
							String ext1 = getFileExtension(f1);
							String ext2 = getFileExtension(f2);
							return ext1.compareToIgnoreCase(ext2);
						case SORT_BY_DATE:
						default:
							return Long.compare(f2.lastModified(), f1.lastModified());
					}
				}
			});

        masterList.clear();
        if (currentSortOrder != SORT_BY_DATE) {
            for (File file : files) {
                masterList.add(new StorageBrowserAdapter.FileItem(file));
            }
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
            String currentHeaderDate = "";
            for (File file : files) {
                if(file.isDirectory()){
                    masterList.add(new StorageBrowserAdapter.FileItem(file));
                    continue;
                }
                String resultDate = sdf.format(new Date(file.lastModified()));
                if (!resultDate.equals(currentHeaderDate)) {
                    currentHeaderDate = resultDate;
                    masterList.add(new DateHeader(currentHeaderDate));
                }
                masterList.add(new StorageBrowserAdapter.FileItem(file));
            }
        }
    }

    private String getFileExtension(File file) {
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return name.substring(lastIndexOf);
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(StorageBrowserActivity.this, "Deletion complete. " + deletedCount + " files removed.", Toast.LENGTH_LONG).show();
                operationProgressLayout.setVisibility(View.GONE);
                new ScanFilesTask().execute(new File(currentPath));
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        operationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (FileOperationService.ACTION_OPERATION_PROGRESS.equals(action)) {
                    String text = intent.getStringExtra(FileOperationService.EXTRA_PROGRESS_TEXT);
                    int progress = intent.getIntExtra(FileOperationService.EXTRA_PROGRESS_VALUE, 0);
                    operationProgressLayout.setVisibility(View.VISIBLE);
                    operationProgressText.setText(text);
                    operationProgressBar.setIndeterminate(false);
                    operationProgressBar.setProgress(progress);
                } else if (FileOperationService.ACTION_OPERATION_COMPLETE.equals(action)) {
                    operationProgressLayout.setVisibility(View.GONE);
                    boolean success = intent.getBooleanExtra(FileOperationService.EXTRA_SUCCESS, false);
                    if (success) {
                        Toast.makeText(StorageBrowserActivity.this, "Operation complete.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(StorageBrowserActivity.this, "Operation failed.", Toast.LENGTH_LONG).show();
                    }
                    refreshCurrentDirectory();
                }
            }
        };
        IntentFilter opFilter = new IntentFilter();
        opFilter.addAction(FileOperationService.ACTION_OPERATION_PROGRESS);
        opFilter.addAction(FileOperationService.ACTION_OPERATION_COMPLETE);
        LocalBroadcastManager.getInstance(this).registerReceiver(operationBroadcastReceiver, opFilter);

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    refreshCurrentDirectory();
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
        if (operationBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(operationBroadcastReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
			.setTitle("SD Card Permission Needed")
			.setMessage("To perform this operation on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card and tap 'Allow'.")
			.setPositiveButton("Grant", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					StorageUtils.requestSdCardPermission(StorageBrowserActivity.this);
				}
			})
			.setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    StorageUtils.saveSdCardUri(this, treeUri);
                    Toast.makeText(this, "SD card access granted.", Toast.LENGTH_SHORT).show();
                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mFilesPendingPermission = null;
            mPendingOperation = null;
            mFilePendingPermissionForExtraction = null;
        }
    }

    private void openFileViewer(final File file) {
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) return null;

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(StorageBrowserActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(StorageBrowserActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(StorageBrowserActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(StorageBrowserActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(StorageBrowserActivity.this, TextViewerActivity.class);
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
                    Toast.makeText(StorageBrowserActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        for(Object item : masterList){
            if (item instanceof StorageBrowserAdapter.FileItem) {
                File file = ((StorageBrowserAdapter.FileItem) item).getFile();
                if(file.isFile() && getFileCategory(file.getName()) == category){
                    siblingFiles.add(file.getAbsolutePath());
                }
            }
        }
        return siblingFiles;
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

    private List<File> getSelectedFiles() {
        List<File> selectedFiles = new ArrayList<>();
        for (Object item : adapter.getFilteredItems()) {
            if (item instanceof StorageBrowserAdapter.FileItem) {
                StorageBrowserAdapter.FileItem fileItem = (StorageBrowserAdapter.FileItem) item;
                if (fileItem.isSelected()) {
                    selectedFiles.add(fileItem.getFile());
                }
            }
        }
        return selectedFiles;
    }

    private void updateFooterUI() {
        boolean hasItems = com.hfm.app.ClipboardManager.getInstance().hasItems();
        findViewById(R.id.footer_controls_browser).setVisibility(hasItems ? View.GONE : View.VISIBLE);
        pasteControlsLayout.setVisibility(hasItems ? View.VISIBLE : View.GONE);
    }

    private void performPaste() {
        final File destination = new File(currentPath);
        if (StorageUtils.isFileOnSdCard(this, destination) && !StorageUtils.hasSdCardPermission(this)) {
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    performPaste();
                }
            };
            promptForSdCardPermission();
            return;
        }

        List<File> filesToOperate = com.hfm.app.ClipboardManager.getInstance().getItems();
        com.hfm.app.ClipboardManager.Operation operation = com.hfm.app.ClipboardManager.getInstance().getOperation();

        if (!destination.isDirectory()) {
            Toast.makeText(this, "Destination is not a valid folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, FileOperationService.class);
        serviceIntent.setAction(FileOperationService.ACTION_START_OPERATION);
        serviceIntent.putExtra(FileOperationService.EXTRA_SOURCE_FILES, (Serializable) filesToOperate);
        serviceIntent.putExtra(FileOperationService.EXTRA_DESTINATION_DIR, destination);
        serviceIntent.putExtra(FileOperationService.EXTRA_OPERATION_TYPE, operation);
        ContextCompat.startForegroundService(this, serviceIntent);

        com.hfm.app.ClipboardManager.getInstance().clear();
        updateFooterUI();
    }

    private void showCreateFolderDialog() {
        final File destination = new File(currentPath);
        if (StorageUtils.isFileOnSdCard(this, destination) && !StorageUtils.hasSdCardPermission(this)) {
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    showCreateFolderDialog();
                }
            };
            promptForSdCardPermission();
            return;
        }


        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_create_folder, null);
        final EditText folderNameInput = dialogView.findViewById(R.id.edit_text_folder_name);

        builder.setView(dialogView)
			.setPositiveButton("Create", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int id) {
					String folderName = folderNameInput.getText().toString().trim();
					if (!folderName.isEmpty()) {
						File newFolder = new File(currentPath, folderName);
						if (StorageUtils.createDirectory(StorageBrowserActivity.this, newFolder)) {
							Toast.makeText(StorageBrowserActivity.this, "Folder created.", Toast.LENGTH_SHORT).show();
							refreshCurrentDirectory();
						} else {
							Toast.makeText(StorageBrowserActivity.this, "Failed to create folder.", Toast.LENGTH_SHORT).show();
						}
					}
				}
			})
			.setNegativeButton("Cancel", null);
        builder.create().show();
    }

    public void refreshCurrentDirectory() {
        new ScanFilesTask().execute(new File(currentPath));
    }

    private ArrayList<File> getAllFilesRecursive(File directory) {
        ArrayList<File> fileList = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    fileList.addAll(getAllFilesRecursive(file));
                } else {
                    fileList.add(file);
                }
            }
        }
        return fileList;
    }

    private ArrayList<String> getAllFilePathsRecursive(File directory) {
        ArrayList<String> pathList = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    pathList.addAll(getAllFilePathsRecursive(file));
                }
                pathList.add(file.getAbsolutePath());
            }
        }
        pathList.add(directory.getAbsolutePath());
        return pathList;
    }


    private class GatherFilesForHidingTask extends AsyncTask<File, Void, ArrayList<File>> {
        AlertDialog progressDialog;

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(StorageBrowserActivity.this);
            builder.setView(R.layout.dialog_progress_simple);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected ArrayList<File> doInBackground(File... dirs) {
            return getAllFilesRecursive(dirs[0]);
        }

        @Override
        protected void onPostExecute(ArrayList<File> files) {
            progressDialog.dismiss();
            if (files.isEmpty()) {
                Toast.makeText(StorageBrowserActivity.this, "Folder is empty, nothing to hide.", Toast.LENGTH_SHORT).show();
            } else {
                Intent intent = new Intent(StorageBrowserActivity.this, FileHiderActivity.class);
                intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, files);
                startActivity(intent);
            }
        }
    }

    private class MoveToRecycleTask extends AsyncTask<Void, String, List<File>> {
        private AlertDialog progressDialog;
        private List<File> filesToMove;
        private Context context;

        public MoveToRecycleTask(List<File> filesToMove) {
            this.filesToMove = filesToMove;
            this.context = StorageBrowserActivity.this;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
            builder.setView(dialogView);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected List<File> doInBackground(Void... voids) {
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists()) {
                if (!recycleBinDir.mkdir()) {
                    return new ArrayList<>();
                }
            }

            List<File> movedFiles = new ArrayList<>();
            for (int i = 0; i < filesToMove.size(); i++) {
                File sourceFile = filesToMove.get(i);
                publishProgress("Moving: " + sourceFile.getName());

                if (sourceFile.exists()) {
                    File destFile = new File(recycleBinDir, sourceFile.getName());
                    if (destFile.exists()) {
                        String name = sourceFile.getName();
                        String extension = "";
                        int dotIndex = name.lastIndexOf(".");
                        if (dotIndex > 0 && !sourceFile.isDirectory()) {
                            extension = name.substring(dotIndex);
                            name = name.substring(0, dotIndex);
                        }
                        destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
                    }

                    boolean moveSuccess = false;

                    // First, try a simple rename. This is fast and will work for same-volume moves.
                    if (sourceFile.renameTo(destFile)) {
                        moveSuccess = true;
                    } else {
                        // If rename fails, it's likely a cross-volume move. Fall back to copy-then-delete.
                        Log.w(TAG, "renameTo failed for " + sourceFile.getAbsolutePath() + ". Falling back to copy-delete.");
                        if (StorageUtils.copyFile(context, sourceFile, destFile)) {
                            // Copy was successful, now delete the original.
                            if (StorageUtils.deleteFile(context, sourceFile)) {
                                moveSuccess = true;
                            } else {
                                // CRITICAL: If the original can't be deleted, we must delete the copy
                                // to avoid duplicating the file.
                                Log.e(TAG, "Failed to delete original file " + sourceFile.getAbsolutePath() + " after copy. Deleting copied file to prevent duplication.");
                                destFile.delete();
                                moveSuccess = false;
                            }
                        } else {
                            // The copy operation failed.
                            Log.e(TAG, "Copy-delete fallback failed to copy file: " + sourceFile.getAbsolutePath());
                            moveSuccess = false;
                        }
                    }

                    if (moveSuccess) {
                        movedFiles.add(sourceFile);
                        scanFile(sourceFile);
                        scanFile(destFile);
                    } else {
                        Log.w(TAG, "Failed to move file to recycle bin: " + sourceFile.getAbsolutePath());
                    }
                }
            }
            return movedFiles;
        }

        @Override
        protected void onPostExecute(List<File> movedFiles) {
            progressDialog.dismiss();
            if (movedFiles.isEmpty() && !filesToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move files.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, movedFiles.size() + " item(s) moved to Recycle Bin.", Toast.LENGTH_LONG).show();
            }

            // Instant UI refresh
            if (!movedFiles.isEmpty()) {
                List<Object> itemsToRemove = new ArrayList<>();
                for (Object item : masterList) {
                    if (item instanceof StorageBrowserAdapter.FileItem) {
                        if (movedFiles.contains(((StorageBrowserAdapter.FileItem) item).getFile())) {
                            itemsToRemove.add(item);
                        }
                    }
                }
                masterList.removeAll(itemsToRemove);
                // This is a simplified refresh, a more complex one could remove empty date headers
                adapter.updateMasterList(masterList);
            }
        }

        private void scanFile(File file) {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);
        }
    }
}