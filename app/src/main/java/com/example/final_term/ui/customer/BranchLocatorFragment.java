package com.example.final_term.ui.customer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.FragmentBranchLocatorBinding;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BranchLocatorFragment extends Fragment {
    private FragmentBranchLocatorBinding binding;
    private GoogleMap map;
    private ActivityResultLauncher<String[]> locationPermission;
    private final Map<String, Marker> branchMarkers = new HashMap<>();
    private Marker selectedBranch;
    private Polyline walkingRoute;
    private CancellationTokenSource locationRequest;
    private int routeRequestId;
    private boolean routeStatusActive;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        locationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (binding == null) return;
                    if (hasLocationPermission()) locateUser();
                    else setStatus("Cho phép truy cập vị trí để tìm đường đi bộ.");
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentBranchLocatorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        binding.homeButton.setOnClickListener(v -> {
            androidx.navigation.NavController nav = Navigation.findNavController(v);
            if (!nav.popBackStack(R.id.customerHome, false)) nav.navigate(R.id.customerHome);
        });
        binding.locateButton.setOnClickListener(v -> startRoute());
        binding.searchButton.setOnClickListener(v -> binding.searchInput.showDropDown());
        SupportMapFragment mapFragment = SupportMapFragment.newInstance();
        getChildFragmentManager().beginTransaction().replace(R.id.map_container, mapFragment).commit();
        FragmentBranchLocatorBinding currentBinding = binding;
        mapFragment.getMapAsync(googleMap -> {
            if (binding != currentBinding) return;
            map = googleMap;
            map.getUiSettings().setMapToolbarEnabled(false);
            map.getUiSettings().setMyLocationButtonEnabled(false);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(10.7769, 106.7009), 12f));
            map.setOnMarkerClickListener(marker -> {
                selectBranch(marker);
                return true;
            });
            loadBranches();
            startRoute();
        });
    }

    private void selectBranch(Marker marker) {
        selectedBranch = marker;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.getPosition(), 16f));
        marker.showInfoWindow();
        startRoute();
    }

    private void startRoute() {
        if (map == null) return;
        routeStatusActive = true;
        if (hasLocationPermission()) locateUser();
        else {
            setStatus("Cho phép truy cập vị trí để tìm đường đi bộ.");
            locationPermission.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void locateUser() {
        if (map == null || !hasLocationPermission()) return;
        int requestId = ++routeRequestId;
        if (locationRequest != null) locationRequest.cancel();
        locationRequest = new CancellationTokenSource();
        if (walkingRoute != null) {
            walkingRoute.remove();
            walkingRoute = null;
        }
        String branchId = selectedBranch == null ? null : (String) selectedBranch.getTag();
        setStatus("Đang xác định vị trí của bạn…");
        try {
            map.setMyLocationEnabled(true);
            LocationServices.getFusedLocationProviderClient(requireActivity())
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, locationRequest.getToken())
                    .addOnSuccessListener(location -> {
                        if (!isCurrentRoute(requestId)) return;
                        if (location == null) {
                            setStatus("Chưa lấy được vị trí. Hãy bật dịch vụ định vị và thử lại.");
                            return;
                        }
                        if (branchId == null) {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                    new LatLng(location.getLatitude(), location.getLongitude()), 14f));
                        }
                        setStatus(branchId == null ? "Đang tìm chi nhánh gần nhất theo đường đi bộ…"
                                : "Đang tìm đường đi bộ tới chi nhánh đã chọn…");
                        requestWalkingRoute(location, branchId, requestId);
                    })
                    .addOnFailureListener(error -> {
                        if (isCurrentRoute(requestId)) {
                            setStatus("Chưa lấy được vị trí. Hãy bật dịch vụ định vị và thử lại.");
                        }
                    });
        } catch (SecurityException ignored) {
            setStatus("Không có quyền truy cập vị trí.");
        }
    }

    private boolean isCurrentRoute(int requestId) {
        return binding != null && map != null && requestId == routeRequestId;
    }

    private void requestWalkingRoute(Location location, @Nullable String branchId, int requestId) {
        try {
            JSONObject body = new JSONObject();
            body.put("latitude", location.getLatitude());
            body.put("longitude", location.getLongitude());
            if (branchId != null) body.put("branchId", branchId);
            FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
            app.getAppContainer().getBackendApiClient().post("/branches/routes", body.toString())
                    .addOnSuccessListener(response -> {
                        if (!isCurrentRoute(requestId)) return;
                        if (response.statusCode < 200 || response.statusCode >= 300) {
                            setStatus(routeFailureMessage(response.body, branchId));
                            return;
                        }
                        try {
                            JSONObject route = new JSONObject(response.body);
                            JSONObject branch = route.getJSONObject("branch");
                            LatLng destination = new LatLng(branch.getDouble("latitude"), branch.getDouble("longitude"));
                            String name = branch.optString("name", "Chi nhánh gần nhất");
                            List<LatLng> points = decodePolyline(route.getString("encodedPolyline"));
                            if (points.size() < 2) {
                                setStatus("Không có dữ liệu để vẽ tuyến đi bộ.");
                                return;
                            }
                            walkingRoute = map.addPolyline(new PolylineOptions().addAll(points)
                                    .color(0xff2558d8).width(11f));
                            showRouteBounds(location, destination, points);
                            int meters = route.optInt("distanceMeters", 0);
                            String distance = meters >= 1000 ? String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
                                    : meters + " m";
                            String duration = route.optString("duration", "").replace("s", "");
                            long seconds = 0;
                            try { seconds = (long) Double.parseDouble(duration); } catch (NumberFormatException ignored) { }
                            String time = seconds >= 3600 ? (seconds / 3600) + " giờ " + ((seconds % 3600) / 60) + " phút"
                                    : Math.max(1, seconds / 60) + " phút";
                            setStatus(name + " · đi bộ " + distance + " · khoảng " + time);
                        } catch (JSONException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
                            setStatus("Đã tìm thấy tuyến nhưng không đọc được thông tin tuyến.");
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (isCurrentRoute(requestId)) {
                            setStatus("Không kết nối được dịch vụ chỉ đường đi bộ.");
                        }
                    });
        } catch (JSONException ignored) {
            setStatus("Không thể gửi vị trí để tìm tuyến.");
        }
    }

    private void showRouteBounds(Location location, LatLng destination, List<LatLng> points) {
        LatLngBounds.Builder bounds = new LatLngBounds.Builder()
                .include(new LatLng(location.getLatitude(), location.getLongitude()))
                .include(destination);
        for (LatLng point : points) bounds.include(point);
        int padding = (int) (64 * getResources().getDisplayMetrics().density);
        int width = binding.mapContainer.getWidth();
        int height = binding.mapContainer.getHeight();
        if (width > 2 * padding && height > 2 * padding) {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), width, height, padding));
        }
    }

    private String routeFailureMessage(String body, @Nullable String branchId) {
        String code = "";
        try { code = new JSONObject(body).optString("error", ""); } catch (Exception ignored) { }
        if ("BRANCHES_UNAVAILABLE".equals(code)) return branchId == null
                ? "Chưa có chi nhánh hoạt động với tọa độ hợp lệ."
                : "Chi nhánh đã chọn không còn khả dụng để chỉ đường.";
        if ("WALKING_ROUTE_UNAVAILABLE".equals(code)) return branchId == null
                ? "Google Maps không tìm được đường đi bộ tới các chi nhánh hiện có."
                : "Google Maps không tìm được đường đi bộ tới chi nhánh đã chọn.";
        if ("ROUTES_PROVIDER_UNAVAILABLE".equals(code)) return "Google Routes API từ chối yêu cầu. Xem log backend để biết chi tiết.";
        if ("ROUTES_API_NOT_CONFIGURED".equals(code)) return "Backend chưa được cấu hình Google Routes API key.";
        if (!code.isEmpty()) return "Không tạo được tuyến (" + code + "). Xem log backend.";
        return "Chưa thể tạo tuyến đi bộ. Các chi nhánh vẫn hiển thị trên bản đồ.";
    }

    private List<LatLng> decodePolyline(String encoded) {
        List<LatLng> points = new ArrayList<>();
        int index = 0, latitude = 0, longitude = 0;
        while (index < encoded.length()) {
            int[] result = decodeValue(encoded, index);
            latitude += result[0]; index = result[1];
            result = decodeValue(encoded, index);
            longitude += result[0]; index = result[1];
            points.add(new LatLng(latitude / 1E5, longitude / 1E5));
        }
        return points;
    }

    private int[] decodeValue(String encoded, int index) {
        int result = 0, shift = 0, value;
        do {
            value = encoded.charAt(index++) - 63;
            result |= (value & 0x1f) << shift;
            shift += 5;
        } while (value >= 0x20);
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : result >> 1, index};
    }

    private void loadBranches() {
        FragmentBranchLocatorBinding currentBinding = binding;
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient().get("/branches?limit=100")
                .addOnSuccessListener(response -> {
                    if (binding != currentBinding) return;
                    if (response.statusCode < 200 || response.statusCode >= 300) {
                        setBranchStatus("Không tải được danh sách chi nhánh. Vui lòng thử lại.");
                        return;
                    }
                    try {
                        JSONArray branches = new JSONObject(response.body).optJSONArray("items");
                        if (branches == null || branches.length() == 0) {
                            setBranchStatus("Chưa có dữ liệu chi nhánh trong khu vực.");
                            return;
                        }
                        ArrayList<String> suggestions = new ArrayList<>();
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject branch = branches.getJSONObject(i);
                            String branchId = branch.optString("id", "");
                            double lat = branch.optDouble("latitude", Double.NaN);
                            double lng = branch.optDouble("longitude", Double.NaN);
                            if (branchId.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lng)
                                    || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                            String name = branch.optString("name", "Chi nhánh Nimbus");
                            String address = branch.optString("address", "");
                            String label = address.isEmpty() ? name : name + " · " + address;
                            Marker marker = map.addMarker(new MarkerOptions().position(new LatLng(lat, lng))
                                    .title(name).snippet(address));
                            if (marker != null) {
                                marker.setTag(branchId);
                                branchMarkers.put(label, marker);
                                suggestions.add(label);
                            }
                        }
                        binding.searchInput.setAdapter(new ArrayAdapter<>(requireContext(),
                                android.R.layout.simple_dropdown_item_1line, suggestions));
                        binding.searchInput.setOnItemClickListener((parent, itemView, position, id) -> {
                            String selected = (String) parent.getItemAtPosition(position);
                            Marker marker = branchMarkers.get(selected);
                            if (marker != null) selectBranch(marker);
                        });
                        setBranchStatus("Chọn một chi nhánh để tìm đường đi bộ.");
                    } catch (JSONException ignored) {
                        setBranchStatus("Dữ liệu chi nhánh không đúng định dạng.");
                    }
                })
                .addOnFailureListener(error -> {
                    if (binding == currentBinding) {
                        setBranchStatus("Không kết nối được máy chủ để tải chi nhánh.");
                    }
                });
    }

    private void setBranchStatus(String text) {
        if (!routeStatusActive) setStatus(text);
    }

    private void setStatus(String text) {
        if (binding != null) binding.statusText.setText(text);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        routeRequestId++;
        if (locationRequest != null) locationRequest.cancel();
        locationRequest = null;
        selectedBranch = null;
        walkingRoute = null;
        branchMarkers.clear();
        routeStatusActive = false;
        binding = null;
        map = null;
    }
}
