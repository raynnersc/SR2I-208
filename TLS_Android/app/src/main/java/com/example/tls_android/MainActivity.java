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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

        Button btnConnect = findViewById(R.id.button_connect_bluetooth);
        btnConnect.setOnClickListener(v -> {
            if(device != null) {
                bluetoothConnect();
                appendToLog("Connected!");
            } else {
                Toast.makeText(this, "Please select a device from the list.", Toast.LENGTH_SHORT).show();
            }
        });

        //send msg OFF to the server before turning Off the bluetooth
        Button btnDisconnect = findViewById(R.id.button_disconnect_bluetooth);
        btnDisconnect.setOnClickListener(v -> {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                bluetoothDisconnect();
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
                        sendData("SEND" + text);
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
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && !bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.startDiscovery();
        }
    }

    @SuppressLint("MissingPermission")
    private void bluetoothConnect() {
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            socket = (BluetoothSocket) m.invoke(device, 1);
            socket.connect();
            startListening();
        } catch (IOException e) {
            Log.e("BluetoothConnection", "Failed to connect: " + e.getMessage());
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void bluetoothDisconnect() {
        try {
            if (socket != null) {
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