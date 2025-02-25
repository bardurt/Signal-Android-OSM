package org.thoughtcrime.securesms.maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.bardurt.omvlib.map.core.GeoPosition;
import com.bardurt.omvlib.map.core.OmvMap;
import com.bardurt.omvlib.map.core.OmvMapView;
import com.bardurt.omvlib.map.core.OmvMarker;
import com.google.android.gms.maps.model.LatLng;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Allows selection of an address from a google map.
 * <p>
 * Based on https://github.com/suchoX/PlacePicker
 */
public final class PlacePickerActivity extends AppCompatActivity {

  private static final String TAG = Log.tag(PlacePickerActivity.class);

  // If it cannot load location for any reason, it defaults to the prime meridian.
  private static final LatLng PRIME_MERIDIAN = new LatLng(51.4779, -0.0015);
  private static final String ADDRESS_INTENT = "ADDRESS";
  private static final float  ZOOM           = 17.0f;

  private static final int                   ANIMATION_DURATION     = 250;
  private static final OvershootInterpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator();
  public static final  String                KEY_CHAT_COLOR         = "chat_color";

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  private SingleAddressBottomSheet bottomSheet;
  private Address                  currentAddress;
  private LatLng                   initialLocation;
  private LatLng                   currentLocation = new LatLng(0, 0);
  private OmvMapView               omvMapView;
  private OmvMap                   omvMap;
  private Handler                  handler         = new Handler(Looper.getMainLooper());
  private ExecutorService          executor        = Executors.newSingleThreadExecutor();

  public static void startActivityForResultAtCurrentLocation(@NonNull Fragment fragment, int requestCode, @ColorInt int chatColor) {
    fragment.startActivityForResult(new Intent(fragment.requireActivity(), PlacePickerActivity.class).putExtra(KEY_CHAT_COLOR, chatColor), requestCode);
  }

  public static AddressData addressFromData(@NonNull Intent data) {
    return data.getParcelableExtra(ADDRESS_INTENT);
  }

  @SuppressLint("MissingInflatedId")
  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dynamicTheme.onCreate(this);

    setContentView(R.layout.activity_place_picker);

    bottomSheet = findViewById(R.id.bottom_sheet);
    View markerImage = findViewById(R.id.marker_image_view);
    View fab         = findViewById(R.id.place_chosen_button);

    ViewCompat.setBackgroundTintList(fab, ColorStateList.valueOf(getIntent().getIntExtra(KEY_CHAT_COLOR, Color.RED)));
    fab.setOnClickListener(v -> finishWithAddress());

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    {
      new LocationRetriever(this, this, location -> {
        setInitialLocation(new LatLng(location.getLatitude(), location.getLongitude()));
      }, () -> {
        Log.w(TAG, "Failed to get location.");
        setInitialLocation(PRIME_MERIDIAN);
      });
    } else {
      Log.w(TAG, "No location permissions");
      setInitialLocation(PRIME_MERIDIAN);
    }


    omvMapView = findViewById(R.id.map);
    if (omvMapView == null) {
      throw new AssertionError("No map fragment");
    }

    omvMapView.getMapAsync(omvMap -> {
      this.omvMap = omvMap;
      if (isLocationPermissionEnabled()) {
        omvMap.setMyLocationEnabled(true);
      }

      omvMap.setMyLocationEnabled(isLocationPermissionEnabled());
      omvMap.showLayerOptions(false);

      omvMap.setOnCameraMoveStartedListener(() -> {
        markerImage.animate()
                   .translationY(-75f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        bottomSheet.hide();
      });

      omvMap.setOnCameraIdleListener(() -> {
        markerImage.animate()
                   .translationY(0f)
                   .setInterpolator(OVERSHOOT_INTERPOLATOR)
                   .setDuration(ANIMATION_DURATION)
                   .start();

        LatLng latLng = new LatLng(omvMapView.getMap().getCenter().getLatitude(), omvMapView.getMap().getCenter().getLongitude());
        setCurrentLocation(latLng);
      });

    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  private void setInitialLocation(@NonNull LatLng latLng) {
    initialLocation = latLng;

    moveMapToInitialIfPossible();
  }

  private void moveMapToInitialIfPossible() {
    if (initialLocation != null && omvMapView != null) {
      Log.d(TAG, "Moving map to initial location");
      omvMapView.getMap().moveCamera(new GeoPosition(initialLocation.latitude, initialLocation.longitude), ZOOM);
      setCurrentLocation(initialLocation);
    }
  }

  private void setCurrentLocation(LatLng location) {
    currentLocation = location;
    bottomSheet.showLoading();
    lookupAddress(location);
  }

  private void finishWithAddress() {
    Intent      returnIntent = new Intent();
    String      address      = currentAddress != null && currentAddress.getAddressLine(0) != null ? currentAddress.getAddressLine(0) : "";
    AddressData addressData  = new AddressData(currentLocation.latitude, currentLocation.longitude, address);
    omvMap.addMarker(new OmvMarker(omvMap.getCenter(), "", null));
    SimpleProgressDialog.DismissibleDialog dismissibleDialog = SimpleProgressDialog.showDelayed(this, 10, 10);

    omvMapView.getMap().snapShot(bitmap -> {
      byte[] blob = BitmapUtil.toByteArray(bitmap);
      Uri uri = BlobProvider.getInstance()
                            .forData(blob)
                            .withMimeType(MediaUtil.IMAGE_JPEG)
                            .createForSingleSessionInMemory();
      returnIntent.putExtra(ADDRESS_INTENT, addressData);
      returnIntent.setData(uri);
      dismissibleDialog.dismiss();
      setResult(RESULT_OK, returnIntent);
      finish();
    });
  }

  private boolean isLocationPermissionEnabled() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  private void lookupAddress(@Nullable LatLng target) {
    if (target == null) {
      return;
    }
    executor.execute(new AddressLookupThread(
        new Geocoder(this, Locale.getDefault()),
        target.latitude,
        target.longitude,
        this::updateAddress,
        handler

    ));
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (omvMap != null) {
      omvMap.destroy();
    }

    executor.shutdown();
  }

  public void updateAddress(Address address) {
    currentAddress = address;
    if (address != null) {
      bottomSheet.showResult(address.getLatitude(), address.getLongitude(), addressToShortString(address), addressToString(address));
    } else {
      bottomSheet.hide();
    }
  }

  public static class AddressLookupThread implements Runnable {
    private final String   TAG = Log.tag(AddressLookupThread.class);
    private final Geocoder geocoder;
    private final double   latitude;
    private final double   longitude;
    private final Listener listener;
    private final Handler  mainThread;

    public AddressLookupThread(Geocoder geocoder,
                               double latitude,
                               double longitude,
                               Listener listener,
                               Handler mainThread
    )
    {
      this.geocoder   = geocoder;
      this.latitude   = latitude;
      this.longitude  = longitude;
      this.listener   = listener;
      this.mainThread = mainThread;

    }

    @Override public void run() {

      LatLng        latLng      = new LatLng(latitude, longitude);
      List<Address> addressList = null;

      try {
        addressList = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
      } catch (IOException e) {
        Log.w(TAG, "Failed to get address from location", e);
      }

      if (addressList != null) {
        if (!addressList.isEmpty()) {
          final Address address = addressList.get(0);
          mainThread.post(() -> listener.onAddressReady(address));
        }
      }
    }


    public interface Listener {
      void onAddressReady(Address address);
    }
  }

  private static @NonNull String addressToString(@Nullable Address address) {
    return address != null ? address.getAddressLine(0) : "";
  }

  private static @NonNull String addressToShortString(@Nullable Address address) {
    if (address == null) return "";

    String   addressLine = address.getAddressLine(0);
    String[] split       = addressLine.split(",");

    if (split.length >= 3) {
      return split[1].trim() + ", " + split[2].trim();
    } else if (split.length == 2) {
      return split[1].trim();
    } else return split[0].trim();
  }


}
