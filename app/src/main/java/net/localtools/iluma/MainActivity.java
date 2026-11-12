package net.localtools.iluma;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Experimental, offline ILUMA i Bluetooth Low Energy client.
 * No network permission, no account and no geographic checks.
 * Protocol definitions are derived from https://github.com/hauntedfail/iqos (GPL-3.0).
 */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 120;
    private static final UUID IQOS_SERVICE = UUID.fromString("daebb240-b041-11e4-9e45-0002a5d5c51b");
    private static final UUID IQOS_BATTERY = UUID.fromString("f8a54120-b041-11e4-9be7-0002a5d5c51b");
    private static final UUID IQOS_SCP = UUID.fromString("e16c6e20-b041-11e4-a4c3-0002a5d5c51b");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID MODEL = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    private static final UUID FIRMWARE = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    private static final int READ = 1, WRITE = 2, SUBSCRIBE = 3;
    private static class Operation {
        final int kind;
        final BluetoothGattCharacteristic characteristic;
        final byte[] payload;
        final String name;
        Operation(int k, BluetoothGattCharacteristic c, byte[] p, String n) {
            kind=k; characteristic=c; payload=p; name=n;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Operation> queue = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic battery, scp;
    private Operation active;
    private Runnable operationTimeout;
    private boolean scanning, ready;
    private TextView status, info, journal;
    private LinearLayout discoveredDevices, controls;
    private Button refreshButton;
    private String deviceName="";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView page = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 46, 24, 40);
        page.addView(content);
        setContentView(page);
        label(content, "ILUMA Local", 26);
        label(content, "Deneysel Android BLE istemcisi · İnternet/hesap gerekmez", 14);
        status = label(content, "Bağlantı yok", 17);
        button(content, "Bluetooth cihazlarını tara", this::startScan);
        button(content, "Bağlantıyı kes", this::disconnect);
        discoveredDevices = new LinearLayout(this);
        discoveredDevices.setOrientation(LinearLayout.VERTICAL);
        content.addView(discoveredDevices);
        info = label(content, "Cihaz bilgileri:", 16);
        refreshButton=button(content, "Bilgileri yenile", this::refresh);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        content.addView(controls);
        label(controls, "ILUMA i ayarları (deneysel)", 19);
        button(controls, "FlexPuff aç", () -> change("FlexPuff aç", "00 D2 45 22 03 01 00 00 0A", "00 D2 05 22 03 00 00 00 17"));
        button(controls, "FlexPuff kapat", () -> change("FlexPuff kapat", "00 D2 45 22 03 00 00 00 0A", "00 D2 05 22 03 00 00 00 17"));
        button(controls, "FlexBattery Eco", () -> change("FlexBattery Eco", "00 C9 44 25 01 00 00 00 4D", "00 C9 00 25 FB"));
        button(controls, "FlexBattery Performance", () -> change("FlexBattery Performance", "00 C9 44 25 00 00 00 00 5B", "00 C9 00 25 FB"));
        button(controls, "Pause Mode aç", () -> change("Pause aç", "00 C9 47 24 02 01 00 00 05", "00 C9 07 24 02 00 00 00 18"));
        button(controls, "Pause Mode kapat", () -> change("Pause kapat", "00 C9 47 24 02 00 00 00 6E", "00 C9 07 24 02 00 00 00 18"));
        button(controls, "AutoStart aç", () -> change("AutoStart aç", "00 C9 47 24 01 01 00 00 3F", null));
        button(controls, "AutoStart kapat", () -> change("AutoStart kapat", "00 C9 47 24 01 00 00 00 54", null));
        button(controls, "Smart Gesture aç", () -> change("Gesture aç", "00 C9 47 24 04 01 00 00 3C", null));
        button(controls, "Smart Gesture kapat", () -> change("Gesture kapat", "00 C9 47 24 04 00 00 00 57", null));
        button(controls, "LED parlaklığı yükselt", () -> brightness(true));
        button(controls, "LED parlaklığı düşür", () -> brightness(false));
        journal=label(content, "Tanılama günlüğü:", 12);
        updateControls();
    }

    private TextView label(LinearLayout parent, String title, int sp) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextSize(sp);
        item.setTextColor(Color.rgb(35,44,55));
        item.setPadding(0, 13, 0, 13);
        parent.addView(item);
        return item;
    }
    private Button button(LinearLayout parent, String title, Runnable action) {
        Button b=new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, new LinearLayout.LayoutParams(-1,-2));
        return b;
    }
    private void log(String text) {
        main.post(() -> {
            String previous=journal.getText().toString();
            if(previous.length()>6500) previous="Tanılama günlüğü:\n";
            journal.setText(previous+"\n"+text);
        });
    }
    private void updateControls() {
        if(refreshButton!=null) refreshButton.setEnabled(ready);
        if(controls!=null) for(int i=0;i<controls.getChildCount();i++) {
            View v=controls.getChildAt(i);
            if(v instanceof Button) v.setEnabled(ready && !deviceName.toUpperCase(Locale.ROOT).contains("I ONE"));
        }
    }
    private boolean hasPermissions() {
        if(Build.VERSION.SDK_INT>=31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestBluetoothPermissions() {
        if(Build.VERSION.SDK_INT>=31) requestPermissions(
                new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int req,String[] names,int[] grants) {
        super.onRequestPermissionsResult(req,names,grants);
        if(req==PERMISSION_REQUEST && hasPermissions()) startScan();
        else if(req==PERMISSION_REQUEST) status.setText("Bluetooth izni verilmedi.");
    }
    private void startScan() {
        if(!hasPermissions()) { requestBluetoothPermissions(); return; }
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null || !adapter.isEnabled()) { status.setText("Telefonun Bluetooth özelliğini aç."); return; }
        stopScan();
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null) { status.setText("BLE tarayıcı kullanılamıyor."); return; }
        seen.clear(); discoveredDevices.removeAllViews();
        scanning=true; status.setText("15 saniye boyunca taranıyor…");
        try { scanner.startScan(scanCallback); }
        catch(Exception e) { scanning=false; status.setText("Tarama hatası: "+e.getMessage()); return; }
        main.postDelayed(() -> { if(scanning){stopScan();status.setText("Tarama bitti. Cihaz bulunamadıysa eşleştirme modunu kontrol et.");} },15000);
    }
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int callbackType,ScanResult result) {
            if(result==null || result.getDevice()==null) return;
            BluetoothDevice device=result.getDevice();
            String name=null;
            try {
                if(result.getScanRecord()!=null) name=result.getScanRecord().getDeviceName();
                if(name==null) name=device.getName();
            } catch(SecurityException ignored) {return;}
            if(name==null || name.trim().isEmpty()) return;
            String address;
            try { address=device.getAddress(); } catch(SecurityException e){return;}
            if(!seen.add(address)) return;
            final String finalName=name;
            main.post(() -> button(discoveredDevices, finalName+" · "+address, () -> connect(device,finalName)));
        }
        @Override public void onScanFailed(int code) {
            main.post(() -> status.setText("Tarama başarısız: "+code));
        }
    };
    private void stopScan() {
        if(scanning && scanner!=null && hasPermissions()) {
            try { scanner.stopScan(scanCallback); } catch(Exception ignored){}
        }
        scanning=false;
    }
    private void connect(BluetoothDevice device,String name) {
        stopScan(); disconnect();
        deviceName=name; status.setText("Bağlanıyor: "+name);
        if(!hasPermissions()) {requestBluetoothPermissions();return;}
        try {gatt=device.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);}
        catch(Exception e) {status.setText("Bağlantı başarısız: "+e.getMessage());}
    }
    private void disconnect() {
        clearOperations(); ready=false;
        if(gatt!=null) {
            BluetoothGatt old=gatt; gatt=null;
            try {old.disconnect(); old.close();}catch(Exception ignored){}
        }
        battery=null;scp=null;deviceName=""; updateControls();
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state) {
            main.post(() -> {
                if(g!=gatt) return;
                if(code==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
                    status.setText("Bağlandı; servisler aranıyor…");
                    try { if(!g.discoverServices()) status.setText("Servis keşfi başlatılamadı."); }
                    catch(Exception e) { status.setText("Servis hatası: "+e.getMessage()); }
                } else if(state==BluetoothProfile.STATE_DISCONNECTED || code!=BluetoothGatt.GATT_SUCCESS) {
                    status.setText("Bağlantı kesildi (GATT "+code+")."); disconnect();
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int code) {
            main.post(() -> {
                if(g!=gatt) return;
                if(code!=BluetoothGatt.GATT_SUCCESS) {status.setText("GATT servis hatası: "+code);return;}
                BluetoothGattService service=g.getService(IQOS_SERVICE);
                if(service==null) {status.setText("Bu cihazda ILUMA BLE servisi bulunamadı.");return;}
                battery=service.getCharacteristic(IQOS_BATTERY);
                scp=service.getCharacteristic(IQOS_SCP);
                if(scp==null) {status.setText("SCP kontrol karakteristiği bulunamadı.");return;}
                ready=true;status.setText("Bağlandı: "+deviceName);
                info.setText("Cihaz bilgileri:");
                updateControls();
                enqueue(new Operation(SUBSCRIBE,scp,null,"SCP bildirimlerini aç"));
                BluetoothGattService infos=g.getService(INFO_SERVICE);
                if(infos!=null) {
                    BluetoothGattCharacteristic model=infos.getCharacteristic(MODEL);
                    BluetoothGattCharacteristic firmware=infos.getCharacteristic(FIRMWARE);
                    if(model!=null) enqueue(new Operation(READ,model,null,"Model"));
                    if(firmware!=null) enqueue(new Operation(READ,firmware,null,"Yazılım"));
                }
                refresh();
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code) {
            main.post(() -> {
                if(g!=gatt) return;
                log("Bildirim aboneliği: "+code);
                finish();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic ch,int code) {
            handleRead(g,ch,ch.getValue(),code);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value,int code) {
            handleRead(g,ch,value,code);
        }
        private void handleRead(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value,int code) {
            main.post(() -> {
                if(g!=gatt)return;
                if(code==BluetoothGatt.GATT_SUCCESS && value!=null) {
                    if(ch.getUuid().equals(IQOS_BATTERY)) {
                        if(value.length>=3) detail("Pil: "+(value[2]&255)+"%");
                        else log("Pil verisi: "+asHex(value));
                    } else if(ch.getUuid().equals(MODEL))detail("Model: "+new String(value,StandardCharsets.UTF_8));
                    else if(ch.getUuid().equals(FIRMWARE))detail("Firmware: "+new String(value,StandardCharsets.UTF_8));
                } else log("GATT okuma hatası: "+code);
                finish();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic ch,int code) {
            main.post(() -> {
                if(g!=gatt)return;
                log((active==null?"SCP yazma":active.name)+": "+(code==BluetoothGatt.GATT_SUCCESS?"gönderildi":"hata "+code));
                finish();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic ch) {
            handleNotification(g,ch,ch.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value) {
            handleNotification(g,ch,value);
        }
        private void handleNotification(BluetoothGatt g,BluetoothGattCharacteristic ch,byte[] value) {
            main.post(() -> {if(g==gatt && ch.getUuid().equals(IQOS_SCP) && value!=null) decode(value);});
        }
    };
    private void detail(String value) {
        String previous=info.getText().toString();
        if(previous.length()>1200) previous="Cihaz bilgileri:";
        info.setText(previous+"\n"+value);
    }
    private void enqueue(Operation op) {queue.add(op);}
    private void clearOperations() {
        queue.clear();active=null;
        if(operationTimeout!=null) main.removeCallbacks(operationTimeout);
        operationTimeout=null;
    }
    private void runQueue() {
        if(!ready || gatt==null || active!=null || queue.isEmpty()) return;
        Operation op=queue.poll();active=op;
        boolean ok=false;
        try {
            if(op.kind==READ) ok=gatt.readCharacteristic(op.characteristic);
            if(op.kind==SUBSCRIBE) {
                ok=gatt.setCharacteristicNotification(op.characteristic,true);
                BluetoothGattDescriptor d=op.characteristic.getDescriptor(CCCD);
                if(ok && d!=null) {
                    if(Build.VERSION.SDK_INT>=33)
                        ok=gatt.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothGatt.GATT_SUCCESS;
                    else {d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);ok=gatt.writeDescriptor(d);}
                } else ok=false;
            }
            if(op.kind==WRITE) {
                if(Build.VERSION.SDK_INT>=33)
                    ok=gatt.writeCharacteristic(op.characteristic,op.payload,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothGatt.GATT_SUCCESS;
                else {
                    op.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    op.characteristic.setValue(op.payload);
                    ok=gatt.writeCharacteristic(op.characteristic);
                }
            }
        } catch(Exception e) {log(op.name+" hata: "+e.getMessage());}
        if(!ok) {log(op.name+" başlatılamadı.");finish();return;}
        operationTimeout=() -> {
            if(active==op) {log(op.name+" zaman aşımı.");finish();}
        };
        main.postDelayed(operationTimeout,4500);
    }
    private void finish() {
        if(operationTimeout!=null)main.removeCallbacks(operationTimeout);
        operationTimeout=null;active=null;
        main.postDelayed(this::runQueue,100);
    }
    private static byte[] hex(String value) {
        String compact=value.replace(" ","");
        byte[] bytes=new byte[compact.length()/2];
        for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(compact.substring(i*2,i*2+2),16);
        return bytes;
    }
    private void command(String description,String bytes) {
        if(ready && scp!=null) enqueue(new Operation(WRITE,scp,hex(bytes),description));
    }
    private void refresh() {
        if(!ready)return;
        if(battery!=null)enqueue(new Operation(READ,battery,null,"Pil"));
        command("Telemetri","00 C9 10 02 01 01 75 D6");
        command("Gün sayısı","00 C0 10 02 00 04 38 EF");
        command("Pil voltajı","00 C0 00 21 E7");
        command("FlexPuff oku","00 D2 05 22 03 00 00 00 17");
        command("FlexBattery oku","00 C9 00 25 FB");
        command("Pause Mode oku","00 C9 07 24 02 00 00 00 18");
        command("LED oku","00 C0 02 23 C3");
        runQueue();
    }
    private void change(String name,String write,String readBack) {
        if(!ready)return;
        command(name,write);
        if(readBack!=null)command(name+" doğrula",readBack);
        log("İşlem istendi: "+name+" (cihazda test edilmedi)");
        runQueue();
    }
    private void brightness(boolean high) {
        if(!ready)return;
        command("LED adım 1",high?"00 C0 46 23 64 00 00 00 4F":"00 C0 46 23 1E 00 00 00 E1");
        command("LED adım 2","00 C0 02 23 C3");
        command("LED adım 3",high?"00 C9 44 24 64 00 00 00 34":"00 C9 44 24 1E 00 00 00 9A");
        runQueue();
    }
    private static String asHex(byte[] bytes) {
        StringBuilder sb=new StringBuilder();
        for(byte b:bytes)sb.append(String.format(Locale.US,"%02X ",b&255));
        return sb.toString().trim();
    }
    private void decode(byte[] d) {
        if(d.length<4){log("Kısa SCP: "+asHex(d));return;}
        int a=d[0]&255,b=d[1]&255,c=d[2]&255,e=d[3]&255;
        if(a==0 && b==0x90 && c==0x85 && e==0x22 && d.length>=9 && (d[4]&255)==3) {
            detail("FlexPuff: "+((d[5]&255)==1?"Açık":"Kapalı"));
        } else if(a==0 && b==8 && c==0x84 && e==0x25 && d.length>=9) {
            detail("FlexBattery: "+((d[4]&255)==1?"Eco":"Performance"));
        } else if(a==0 && b==8 && c==0x87 && e==0x24 && d.length>=9) {
            detail("Ayar "+(d[4]&255)+": "+((d[5]&255)==1?"Açık":"Kapalı"));
        } else if(a==0 && b==0xC0 && c==0x86 && e==0x23 && d.length>=9) {
            detail("LED parlaklığı: "+((d[4]&255)==0x64?"Yüksek":"Düşük"));
        } else if(c==0x88 && e==0x21 && d.length>=7) {
            int millivolt=(d[5]&255)|((d[6]&255)<<8);
            detail("Pil voltajı: "+String.format(Locale.US,"%.3f V",millivolt/1000.0));
        } else if(c==0x80 && e==0x02 && d.length>=6) {
            detail("Kullanılan gün: "+((d[4]&255)|((d[5]&255)<<8)));
        } else if(c==0x90 && e==0x22 && d.length>=14) {
            for(int i=6;i+8<=d.length;i+=8) {
                int value=(d[i+4]&255)|((d[i+5]&255)<<8);
                int tag=d[i+7]&255;
                if(tag==0x8E)detail("Toplam kullanım: "+value);
                if(tag==0x17)detail("Gün sayacı: "+value);
            }
        } else log("SCP: "+asHex(d));
    }
    @Override protected void onDestroy() {
        stopScan();disconnect();super.onDestroy();
    }
}
