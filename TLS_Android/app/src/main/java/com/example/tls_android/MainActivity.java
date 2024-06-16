package com.example.tls_android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.google.android.material.snackbar.Snackbar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.tls_android.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final long DISCOVERY_DURATION = 2000;
    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    private ActivityResultLauncher<Intent> enableBluetoothLauncher;
    private BroadcastReceiver permissionReceiver;
    private BluetoothSocket socket;

    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    ArrayList<String> deviceList = new ArrayList<>();
    ArrayAdapter<String> arrayAdapter;

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
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
        // Set the click listener for the button
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

//        //starts looking for bluetooth devices when we open the application and bluetooth is ON
//        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
//            bluetoothAdapter.startDiscovery();
//        }
        // No método onde inicia a descoberta:
//        bluetoothAdapter.startDiscovery();
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
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address); //get the bt device using its hardware address
//                Log.d("bluetooth device info:", info);
//                Log.d("bluetooth device address:", address);
                appendToLog("bluetooth device info:" + info);
                device.createBond(); //pair with bluetooth device (need to handle what to do next after creating bond)
                listView.setVisibility(View.GONE);
                bluetoothAdapter.cancelDiscovery();
                bluetoothConnect(address);
                appendToLog("Connected!");
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

        /*Button btnConnectBluetooth = findViewById(R.id.button_connect_bluetooth);
        Button btnCreateSecureConnection = findViewById(R.id.btnCreateSecureConnection);
        Button btnSendMessage = findViewById(R.id.btnSendMessage);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        btnConnectBluetooth.setOnClickListener(v -> {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice("device-address"); // Replace with your Bluetooth device address
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH}, REQUEST_ENABLE_BT);
                }
                mBluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
                mBluetoothSocket.connect();
                Toast.makeText(this,"Bluetooth connected!",Toast.LENGTH_SHORT);
            } catch (IOException e) {
                String err_msg = "Failed to connect: " + e.getMessage();
                Toast.makeText(this,err_msg,Toast.LENGTH_SHORT);
            }
        });*/


        /*Button btnConnect = findViewById(R.id.button_connect_bluetooth);
        btnConnect.setOnClickListener(v -> {
            Intent intentOpenBluetoothSettings = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intentOpenBluetoothSettings);
        });*/

        //send msg OFF to the server before turning Off the bluetooth
        Button btnDisconnect = findViewById(R.id.button_disconnect_bluetooth);
        btnDisconnect.setOnClickListener(v -> {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                bluetoothDisconnect();
                appendToLog("Disconnected!");
                bluetoothAdapter.disable();
                Toast.makeText(MainActivity.this, "Bluetooth is now disabled", Toast.LENGTH_SHORT).show();
            }
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

//    @Override
//    protected void onResume() {
//        super.onResume();
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQUEST_BLUETOOTH_CONNECT);
//            return;
//        }
//        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
//            startDiscoveryIfNeeded();
//        }
//    }

//    @Override
//    protected void onPause() {
//        super.onPause();
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQUEST_BLUETOOTH_CONNECT);
//            return;
//        }
//        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
//            bluetoothAdapter.cancelDiscovery();
//        }
//    }

    // Constant for request code
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_CONNECT = 2;
    private static final int REQUEST_DISCOVER_BT = 3;

    private static final int ACCESS_FINE_LOCATION = 4;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Check if location permissions are granted
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    //permission  granted
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        String deviceName = device.getName(); // Device name could be null
                        String deviceHardwareAddress = device.getAddress(); // MAC address
                        deviceList.add((deviceName != null ? deviceName : "Unknown") + "\n" + deviceHardwareAddress);
                        arrayAdapter.notifyDataSetChanged(); //adds device found to the list of bluetooth devices
                    }
                } else {
                    // Permission is not granted, notify the activity to request it
                    Intent permissionIntent = new Intent("com.example.tls_android.REQUEST_PERMISSION");
                    LocalBroadcastManager.getInstance(context).sendBroadcast(permissionIntent);
                }
            }
            //add her else if action is ACTION_BOND_STATE_CHANGED (paired on unpaired with a device)

        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
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
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQUEST_DISCOVER_BT);
//            return;
//        }
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && !bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.startDiscovery();
        }
    }

    @SuppressLint("MissingPermission")
    private void bluetoothConnect(String address) {
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            UUID btUUID;

            if (device.getUuids() != null && device.getUuids().length > 0) {
                btUUID = device.getUuids()[0].getUuid();
            } else {
                btUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // UUID for Serial Port Profile (SPP)
            }
            btUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
            Log.d("BluetoothConnection", "UUID: " + (btUUID != null ? btUUID.toString() : "No UUID available"));

            socket = device.createRfcommSocketToServiceRecord(btUUID);
            socket.connect();
            startListening();
        } catch (IOException e) {
            Log.e("BluetoothConnection", "Failed to connect: " + e.getMessage());
        }
    }

    private void bluetoothDisconnect() {
        try {
            if (socket != null) {
                socket.close();
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

                    runOnUiThread(() -> {
                        appendToLog("Received: " + receivedMessage);
                    });
                }
            } catch (IOException e) {
                Log.e("BluetoothConnection", "Error reading from input stream: " + e.getMessage());
            }
        }).start();
    }

}