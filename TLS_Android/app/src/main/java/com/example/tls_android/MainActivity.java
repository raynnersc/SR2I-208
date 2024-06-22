package com.example.tls_android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.tls_android.databinding.ActivityMainBinding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {

    private static final long DISCOVERY_DURATION = 2000;

    private ActivityResultLauncher<Intent> enableBluetoothLauncher;
    private BroadcastReceiver permissionReceiver;
    private BluetoothSocket socket;
    private BluetoothDevice device;

    // Constant for request code
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_CONNECT = 2;
    private static final int REQUEST_DISCOVER_BT = 3;
    private static final int ACCESS_FINE_LOCATION = 4;

    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    ArrayList<String> deviceList = new ArrayList<>();
    ArrayAdapter<String> arrayAdapter;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        //Request runtime permissions
        //This is crucial for Bluetooth discovery on Android 6.0 and above
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, ACCESS_FINE_LOCATION);
        }

        // Register local BroadcastReceiver
        IntentFilter request_filter = new IntentFilter("com.example.tls_android.REQUEST_PERMISSION");
        permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Request permissions
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(permissionReceiver, request_filter);

        // Initialize the launcher with the result handling logic
        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        // Bluetooth has been enabled
                        Toast.makeText(this, "Bluetooth is now enabled", Toast.LENGTH_SHORT).show();
                    } else {
                        // Bluetooth enabling was cancelled or failed
                        Toast.makeText(this, "Failed to enable Bluetooth", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        //TURN ON Bluetooth on the phone
        Button btnTurnOn = findViewById(R.id.button_turnon_bluetooth);
        btnTurnOn.setOnClickListener(v -> {
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                // Permission has already been granted, proceed with enabling Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                enableBluetoothLauncher.launch(enableBtIntent);
            }
        });

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);
        filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, filter);

        startDiscoveryIfNeeded();
        new Handler().postDelayed(() -> {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }, DISCOVERY_DURATION);

        //display the available bluetooth devices for pairing
        ListView listView = findViewById(R.id.listView);
        arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        listView.setAdapter(arrayAdapter);

        //click on a device to pair with
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String info = ((TextView) view).getText().toString();
                String address = info.substring(info.length() - 17); //extracts address from the info text (last 17 characters)
                device = bluetoothAdapter.getRemoteDevice(address); //get the bt device using its hardware address
                appendToLog("bluetooth device info:" + info);
                device.createBond(); //pair with bluetooth device (need to handle what to do next after creating bond)
                listView.setVisibility(View.GONE);
                bluetoothAdapter.cancelDiscovery();
                appendToLog("Device selected!");
            }
        });

        Button btnToggleList = findViewById(R.id.button_toggle_list);
        btnToggleList.setOnClickListener(v -> {
            if (!(bluetoothAdapter != null && bluetoothAdapter.isEnabled())) {
                listView.setVisibility(View.GONE);
                Toast.makeText(this, "Please enable Bluetooth to view the device list.", Toast.LENGTH_SHORT).show();
            } else {
                if (listView.getVisibility() != View.VISIBLE) {
                    startDiscoveryIfNeeded();
                    listView.setVisibility(View.VISIBLE);
                } else {
                    bluetoothAdapter.cancelDiscovery();
                    listView.setVisibility(View.GONE);
                    deviceList.clear();
                }
            }
        });

        SwitchCompat btnTLS = findViewById(R.id.button_tls);

        Button btnConnect = findViewById(R.id.button_connect_bluetooth);
        btnConnect.setOnClickListener(v -> {
            if (device != null) {
                boolean isTLSEnable = btnTLS.isChecked();
                bluetoothConnect(isTLSEnable);
                appendToLog("Connected!");
            } else {
                Toast.makeText(this, "Please select a device from the list.", Toast.LENGTH_SHORT).show();
            }
        });

        //send msg OFF to the server before turning Off the bluetooth
        Button btnDisconnect = findViewById(R.id.button_disconnect_bluetooth);
        btnDisconnect.setOnClickListener(v -> {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                boolean isTLSEnable = btnTLS.isChecked();
                bluetoothDisconnect(isTLSEnable);
                appendToLog("Disconnected!");
            }
        });

        Button btnClearLog = findViewById(R.id.button_clear_log);
        btnClearLog.setOnClickListener(v -> {
            TextView textLog = findViewById(R.id.text_receive_data);
            textLog.setText("");
        });

        Button btnSend = findViewById(R.id.button_send_data);
        EditText editText = findViewById(R.id.text_send_data);

        btnSend.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
//                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && bluetoothAdapter.getProfileConnectionState(BluetoothProfile.STATE_CONNECTED) != BluetoothProfile.STATE_DISCONNECTED) {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    String text = editText.getText().toString();
                    if (!text.isEmpty()) {
                        appendToLog("Sent: " + text);
                        text = "SEND" + text;
                        boolean isTLSEnable = btnTLS.isChecked();
                        if (isTLSEnable) {
                            try {
                                SSLHandler.sendEncryptedData(text, socket);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        else
                            sendData(text);
                        editText.setText("");  // Clear the input field
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Not connected to any Bluetooth device", Toast.LENGTH_SHORT).show();
                }
            } else {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH}, REQUEST_ENABLE_BT);
            }
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Check if location permissions are granted
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    //permission  granted
                    BluetoothDevice newDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (newDevice != null) {
                        String deviceInfo = (newDevice.getName() != null ? newDevice.getName() : "Unknown") + "\n" + newDevice.getAddress();
                        // Check for duplicate entry
                        if (!deviceList.contains(deviceInfo)) {
                            deviceList.add(deviceInfo);
                            arrayAdapter.notifyDataSetChanged(); // Update the list display
                        }
                    } else {
                        // Permission is not granted, notify the activity to request it
                        Intent permissionIntent = new Intent("com.example.tls_android.REQUEST_PERMISSION");
                        LocalBroadcastManager.getInstance(context).sendBroadcast(permissionIntent);
                    }
                }
                //add her else if action is ACTION_BOND_STATE_CHANGED (paired on unpaired with a device)

            }
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, try to open Bluetooth settings again
                Button btnTurnOn = findViewById(R.id.button_turnon_bluetooth);
                btnTurnOn.performClick();  // Re-attempt enabling Bluetooth
            } else {
                // Permission was denied, handle the failure
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_ENABLE_BT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Button btnSend = findViewById(R.id.button_send_data);
                btnSend.performClick();
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_DISCOVER_BT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Button btnConnect = findViewById(R.id.button_connect_bluetooth);
                btnConnect.performClick();
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == ACCESS_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted
                Button btnExpandCollapseList = findViewById(R.id.button_toggle_list);
                btnExpandCollapseList.performClick();  // Re-attempt enabling Bluetooth
            } else {
                // Permission was denied
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the local receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(permissionReceiver);
        unregisterReceiver(receiver);
    }

    private void appendToLog(String text) {
        TextView textLog = findViewById(R.id.text_receive_data);
        String currentLog = textLog.getText().toString();
        String updatedLog = currentLog + text + "\n";
        textLog.setText(updatedLog);
    }

    @SuppressLint("MissingPermission")
    private void startDiscoveryIfNeeded() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && !bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.startDiscovery();
        }
    }

    @SuppressLint("MissingPermission")
    private void bluetoothConnect(boolean isTLSEnabled) {
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            socket = (BluetoothSocket) m.invoke(device, 3);
            if (socket != null) {
                System.out.println("creating socket bluetooth");
                socket.connect();
                System.out.println("socket bluetooth created");
            }
            if (isTLSEnabled) {
                System.out.println("TLS is enabled - going to TLS connect");
                TLSConnect();
                System.out.println("TLS - receiving data");
                startListeningTLS();
                System.out.println("TLS - data received");
            }
            else
                startListening();
        } catch (IOException e) {
            Log.e("BluetoothConnection", "Failed to connect: " + e.getMessage());
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void bluetoothDisconnect(boolean isTLSEnabled) {
        try {
            if (socket != null) {
                if (isTLSEnabled)
                    SSLHandler.sendEncryptedData("OFF", socket);
                else
                    sendData("OFF");
                socket.close();
                device = null;
            }
        } catch (IOException e) {
            Log.e("BluetoothConnection", "Error closing socket: " + e.getMessage());
        }
    }

    private void sendData(String data) {
        try {
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(data.getBytes());
            outputStream.flush();
            Log.d("BluetoothConnection", "Data sent: " + data);
        } catch (IOException e) {
            Log.e("BluetoothConnection", "Error sending data: " + e.getMessage());
        }
    }

    private void startListening() {
        new Thread(() -> {
            try {
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[1024];
                int bytes;

                while (true) {
                    bytes = inputStream.read(buffer);
                    String receivedMessage = new String(buffer, 0, bytes);
                    Log.d("BluetoothConnection", "Received: " + receivedMessage);

                    runOnUiThread(() -> appendToLog("Received: " + receivedMessage));
                }
            } catch (IOException e) {
                Log.e("BluetoothConnection", "Error reading from input stream: " + e.getMessage());
            }
        }).start();
    }

    private void TLSConnect() {
        try {
            System.out.println("Inside TLS connect");
            SSLHandler.initSSLEngine(socket, this);
        } catch (IOException e) {
            Log.e("TLSConnection", "Failed to connect: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startListeningTLS() {
        new Thread(() -> {
            try {
                while (true) {
                    String receivedMessage = SSLHandler.receiveEncryptedData(socket);
                    if (receivedMessage != null && !receivedMessage.equals("")) {
                        runOnUiThread(() -> {
                            // Handle the received message, for example, display it
                            appendToLog("Received: " + receivedMessage);
                        });
                    }
                }
            } catch (IOException e) {
                Log.e("startListeningTLS", "Failed to receive data", e);
            }
        }).start();
    }
}