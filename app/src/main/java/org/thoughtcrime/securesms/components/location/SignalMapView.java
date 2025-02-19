package org.thoughtcrime.securesms.components.location;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.bardurt.omvlib.map.core.GeoPosition;
import com.bardurt.omvlib.map.core.OmvMap;
import com.bardurt.omvlib.map.core.OmvMapView;
import com.bardurt.omvlib.map.core.OmvMarker;
import com.google.android.gms.maps.model.LatLng;

import org.signal.core.util.concurrent.ListenableFuture;
import org.signal.core.util.concurrent.SettableFuture;
import org.thoughtcrime.securesms.R;

import java.util.concurrent.ExecutionException;

public class SignalMapView extends LinearLayout {

  private OmvMapView   mapView;
  private ImageView imageView;
  private TextView  textView;

  public SignalMapView(Context context) {
    this(context, null);
  }

  public SignalMapView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(context);
  }

  public SignalMapView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }

  private void initialize(Context context) {
    setOrientation(LinearLayout.VERTICAL);
    LayoutInflater.from(context).inflate(R.layout.signal_map_view, this, true);

    this.mapView   = findViewById(R.id.map_view);
    this.imageView = findViewById(R.id.image_view);
    this.textView  = findViewById(R.id.address_view);
  }

  public ListenableFuture<Bitmap> display(final SignalPlace place) {
    final SettableFuture<Bitmap> future = new SettableFuture<>();
    this.mapView.setVisibility(View.VISIBLE);
    this.mapView.getMap().show();
    this.imageView.setVisibility(View.GONE);
    this.textView.setText(place.getDescription());

    mapView.getViewTreeObserver().addOnGlobalLayoutListener(() -> snapshot(place, mapView).addListener(new ListenableFuture.Listener<>() {
      @Override
      public void onSuccess(Bitmap result) {
        future.set(result);
        imageView.setImageBitmap(result);
        imageView.setVisibility(View.VISIBLE);
      }

      @Override
      public void onFailure(ExecutionException e) {
        future.setException(e);
      }
    }));

    return future;
  }

  public static ListenableFuture<Bitmap> snapshot(final LatLng place, @NonNull final OmvMapView omvMapView) {
    final SettableFuture<Bitmap> future = new SettableFuture<>();

    omvMapView.getMap().getMapAsync(() -> {
      omvMapView.getMap().moveCamera(
          new GeoPosition(place.latitude, place.longitude), 13);
      omvMapView.getMap().setBuildingsEnabled(true);
      omvMapView.getMap().setMapType(OmvMap.MapType.NORMAL);
      omvMapView.getMap().addMarker(new OmvMarker(new GeoPosition(place.latitude, place.longitude)));
      omvMapView.getMap().snapShot(future::set);
    });

    return future;
  }

  public static ListenableFuture<Bitmap> snapshot(final SignalPlace place, @NonNull final OmvMapView mapView) {
    return snapshot(place.getLatLong(), mapView);
  }

}
