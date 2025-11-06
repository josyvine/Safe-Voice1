package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;

public class SenderService extends Service {

    private static final String TAG = "SenderService";
    private static final String NOTIFICATION_CHANNEL_ID = "SenderServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_SEND = "com.hfm.app.action.START_SEND";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_RECEIVER_USERNAME = "receiver_username";
    public static final String EXTRA_SECRET_NUMBER = "secret_number";

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private NanoHttpd server;
    private File cloakedFile;
    private String dropRequestId;
    private ListenerRegistration requestListener;

    @Override
    public void onCreate() {
        super.onCreate();
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = mAuth.getCurrentUser();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_SEND.equals(intent.getAction())) {
            final String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            final String receiverUsername = intent.getStringExtra(EXTRA_RECEIVER_USERNAME);
            final String secretNumber = intent.getStringExtra(EXTRA_SECRET_NUMBER);

            Notification notification = buildNotification("Starting Drop Send Service...", true);
            startForeground(NOTIFICATION_ID, notification);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    startSenderProcess(filePath, receiverUsername, secretNumber);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void startSenderProcess(String filePath, String receiverUsername, String secretNumber) {
        File inputFile = new File(filePath);
        if (!inputFile.exists()) {
            Log.e(TAG, "File to send does not exist: " + filePath);
            stopServiceAndCleanup("Error: File not found.");
            return;
        }

        // 1. Data Cloak
        updateNotification("Cloaking data...", true);
        cloakedFile = CloakingManager.cloakFile(this, inputFile, secretNumber);
        if (cloakedFile == null) {
            Log.e(TAG, "Cloaking file failed.");
            stopServiceAndCleanup("Error: Failed to cloak file.");
            return;
        }

        // 2. Start HTTP Server
        try {
            server = new NanoHttpd(cloakedFile);
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server.", e);
            stopServiceAndCleanup("Error: Could not start server.");
            return;
        }

        // 3. Discover Public IP/Port via STUN
        updateNotification("Discovering network address...", true);
        StunClient.StunResult stunResult = StunClient.getPublicIpAddress();
        if (stunResult == null) {
            Log.e(TAG, "STUN discovery failed.");
            stopServiceAndCleanup("Error: Network discovery failed.");
            return;
        }

        // 4. Create Firestore Document
        updateNotification("Creating drop request...", true);
        String senderUsername = generateUsernameFromUid(currentUser.getUid());

        Map<String, Object> dropRequest = new HashMap<>();
        dropRequest.put("senderId", currentUser.getUid());
        dropRequest.put("senderUsername", senderUsername);
        dropRequest.put("receiverUsername", receiverUsername);
        dropRequest.put("filename", inputFile.getName());
        dropRequest.put("filesize", inputFile.length());
        dropRequest.put("status", "pending");
        dropRequest.put("secretNumber", secretNumber);
        dropRequest.put("senderPublicIp", stunResult.publicIp);
        dropRequest.put("senderPublicPort", stunResult.publicPort);
        dropRequest.put("senderLocalPort", server.getListeningPort());
        dropRequest.put("timestamp", System.currentTimeMillis());

        db.collection("drop_requests")
                .add(dropRequest)
                .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(DocumentReference documentReference) {
                        dropRequestId = documentReference.getId();
                        Log.d(TAG, "Drop request created with ID: " + dropRequestId);
                        updateNotification("Waiting for receiver...", true);
                        listenForStatusChange(dropRequestId);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "Failed to create drop request.", e);
                        stopServiceAndCleanup("Error: Failed to create request.");
                    }
                });
    }

    private void listenForStatusChange(String docId) {
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        requestListener = docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e);
                    return;
                }

                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    Log.d(TAG, "Drop request status changed to: " + status);
                    if ("accepted".equals(status)) {
                        updateNotification("Receiver connected. Transferring...", true);
                    } else if ("declined".equals(status)) {
                        stopServiceAndCleanup("Receiver declined the transfer.");
                    } else if ("complete".equals(status)) {
                        stopServiceAndCleanup(null); // Success, no toast
                        // Self-destruct identity
                        if (currentUser != null) {
                            currentUser.delete();
                        }
                    } else if ("error".equals(status)) {
                        stopServiceAndCleanup("An error occurred on the receiver's end.");
                    }
                } else {
                    Log.d(TAG, "Drop request document deleted.");
                    stopServiceAndCleanup(null);
                }
            }
        });
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
        String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }


    private void stopServiceAndCleanup(final String toastMessage) {
        if (toastMessage != null) {
            // This is a simple way to show toast from a background service
            new Handler(getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(SenderService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SenderService onDestroy.");
        if (requestListener != null) {
            requestListener.remove();
        }
        if (server != null) {
            server.stopServer();
        }
        if (cloakedFile != null && cloakedFile.exists()) {
            cloakedFile.delete();
        }
        // If the request wasn't completed, mark it as failed so the receiver knows.
        if (dropRequestId != null) {
            db.collection("drop_requests").document(dropRequestId).get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                @Override
                public void onSuccess(DocumentSnapshot documentSnapshot) {
                    if (documentSnapshot.exists()) {
                        String status = documentSnapshot.getString("status");
                        if ("pending".equals(status) || "accepted".equals(status)) {
                            documentSnapshot.getReference().update("status", "error");
                        }
                    }
                }
            });
        }
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Sender Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing) {
        Notification notification = buildNotification(text, ongoing);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop Sender")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(ongoing)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * A very simple, single-file, single-connection HTTP server.
     */
    private static class NanoHttpd extends Thread {
        private final ServerSocket serverSocket;
        private final File fileToServe;
        private volatile boolean isRunning = true;

        public NanoHttpd(File file) throws IOException {
            super("NanoHttpd Sender Thread");
            this.serverSocket = new ServerSocket(0); // 0 means assign any free port
            this.fileToServe = file;
        }

        public int getListeningPort() {
            return serverSocket.getLocalPort();
        }

        public void stopServer() {
            isRunning = false;
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket.", e);
            }
            this.interrupt();
        }

        @Override
        public void run() {
            try {
                while (isRunning) {
                    final Socket clientSocket = serverSocket.accept();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleRequest(clientSocket);
                        }
                    }).start();
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "ServerSocket accept failed.", e);
                }
            } finally {
                stopServer();
            }
        }

        private void handleRequest(Socket socket) {
            BufferedReader in = null;
            OutputStream out = null;
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = socket.getOutputStream();
                PrintWriter writer = new PrintWriter(out);

                String line = in.readLine();
                if (line == null || !line.startsWith("GET")) {
                    return; // Ignore non-GET requests
                }

                long rangeStart = 0;
                long rangeEnd = -1;

                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Range: bytes=")) {
                        line = line.substring(13);
                        String[] parts = line.split("-");
                        try {
                            rangeStart = Long.parseLong(parts[0]);
                            if (parts.length > 1 && !parts[1].isEmpty()) {
                                rangeEnd = Long.parseLong(parts[1]);
                            }
                        } catch (NumberFormatException e) {
                            // Malformed Range header, ignore.
                        }
                    }
                }

                long fileLen = fileToServe.length();
                if (rangeEnd == -1) {
                    rangeEnd = fileLen - 1;
                }

                if (rangeStart > fileLen || rangeEnd >= fileLen) {
                    writer.println("HTTP/1.1 416 Requested Range Not Satisfiable");
                    writer.println("Content-Range: bytes */" + fileLen);
                    writer.flush();
                    return;
                }

                long contentLength = (rangeEnd - rangeStart) + 1;

                if (rangeStart > 0) {
                    writer.println("HTTP/1.1 206 Partial Content");
                    writer.println("Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileLen);
                } else {
                    writer.println("HTTP/1.1 200 OK");
                }

                writer.println("Content-Type: text/plain");
                writer.println("Content-Length: " + contentLength);
                writer.println("Accept-Ranges: bytes");
                writer.println();
                writer.flush();

                FileInputStream fis = new FileInputStream(fileToServe);
                fis.skip(rangeStart);

                byte[] buffer = new byte[8192];
                long bytesRemaining = contentLength;
                int bytesRead;
                while (bytesRemaining > 0 && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining))) != -1) {
                    out.write(buffer, 0, bytesRead);
                    bytesRemaining -= bytesRead;
                }
                out.flush();
                fis.close();

            } catch (IOException e) {
                Log.e(TAG, "Error handling client request", e);
            } finally {
                try {
                    if (out != null) out.close();
                    if (in != null) in.close();
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
                // Stop server after one successful-ish file transfer
                stopServer();
            }
        }
    }

    /**
     * A simple STUN client to discover public IP and port.
     */
    private static class StunClient {
        private static final String STUN_SERVER = "stun.l.google.com";
        private static final int STUN_PORT = 19302;

        public static class StunResult {
            public String publicIp;
            public int publicPort;
        }

        public static StunResult getPublicIpAddress() {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(3000); // 3-second timeout

                // STUN Binding Request
                byte[] request = new byte[20];
                // Message Type: 0x0001 (Binding Request)
                request[0] = 0x00;
                request[1] = 0x01;
                // Message Length: 0
                request[2] = 0x00;
                request[3] = 0x00;
                // Magic Cookie: 0x2112A442
                request[4] = 0x21;
                request[5] = 0x12;
                request[6] = (byte) 0xA4;
                request[7] = 0x42;
                // Transaction ID (12 bytes, can be random)
                new Random().nextBytes(Arrays.copyOfRange(request, 8, 20));

                InetAddress address = InetAddress.getByName(STUN_SERVER);
                DatagramPacket requestPacket = new DatagramPacket(request, request.length, address, STUN_PORT);
                socket.send(requestPacket);

                byte[] response = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(response, response.length);
                socket.receive(responsePacket);

                return parseStunResponse(responsePacket.getData(), responsePacket.getLength());

            } catch (Exception e) {
                Log.e(TAG, "STUN request failed", e);
                return null;
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }

        private static StunResult parseStunResponse(byte[] data, int length) {
            if (length < 20 || data[0] != 0x01 || data[1] != 0x01) { // Binding Success Response
                return null;
            }

            // Find MAPPED-ADDRESS or XOR-MAPPED-ADDRESS attribute
            int i = 20; // Start after header
            while (i < length) {
                int type = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                int attrLength = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);

                if (type == 0x0001 /* MAPPED-ADDRESS */ || type == 0x0020 /* XOR-MAPPED-ADDRESS */) {
                    // Attribute value starts at i + 4
                    int family = data[i + 5] & 0xFF;
                    if (family == 0x01) { // IPv4
                        int port = ((data[i + 6] & 0xFF) << 8) | (data[i + 7] & 0xFF);
                        byte[] ipBytes = new byte[4];

                        if (type == 0x0020) { // XOR-MAPPED
                            port ^= ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
                            for (int j = 0; j < 4; j++) {
                                ipBytes[j] = (byte) (data[i + 8 + j] ^ data[4 + j]);
                            }
                        } else { // MAPPED
                            System.arraycopy(data, i + 8, ipBytes, 0, 4);
                        }

                        try {
                            String ip = InetAddress.getByAddress(ipBytes).getHostAddress();
                            StunResult result = new StunResult();
                            result.publicIp = ip;
                            result.publicPort = port;
                            Log.d(TAG, "STUN Result: " + ip + ":" + port);
                            return result;
                        } catch (UnknownHostException e) {
                            return null;
                        }
                    }
                }
                i += 4 + attrLength; // Move to next attribute
                if (attrLength % 4 != 0) { // Attributes are padded to 4 bytes
                    i += (4 - (attrLength % 4));
                }
            }
            return null;
        }
    }
}