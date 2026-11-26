package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.content.Intent;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentMobileTopupBinding;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MobileTopupFragment extends Fragment {
    private FragmentMobileTopupBinding binding;
    private BackendApiClient api;
    private final Map<String, List<JSONObject>> productsByNetwork = new LinkedHashMap<>();
    private String selectedNetwork;
    private JSONObject selectedProduct;
    private String sourceAccountId;
    private boolean loading;
    private ActivityResultLauncher<Intent> contactPicker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        contactPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null
                    || result.getData().getData() == null || binding == null) return;
            android.net.Uri contactUri = result.getData().getData();
            try (Cursor cursor = requireContext().getContentResolver().query(contactUri,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
                int numberColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                if (numberColumn < 0) return;
                String phone = normalizePhone(cursor.getString(numberColumn));
                if (!phone.isEmpty()) {
                    binding.phoneInput.setText(phone);
                    binding.phoneInput.setSelection(phone.length());
                }
            } catch (RuntimeException error) {
                binding.phoneInput.setError("Không đọc được số điện thoại đã chọn.");
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMobileTopupBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        api = ((FinalTermApplication) requireActivity().getApplication())
                .getAppContainer().getBackendApiClient();
        sourceAccountId = getArguments() == null ? null : getArguments().getString("sourceAccountId");
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).popBackStack());
        binding.topupHistoryButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.topupButton.setOnClickListener(v -> submitTopup());
        binding.phoneContactButton.setOnClickListener(v -> {
            Intent pickPhone = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            contactPicker.launch(pickPhone);
        });
        binding.carrierAutoButton.setOnClickListener(v -> applySuggestedCarrier());
        binding.phoneInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCarrierHint();
                updateSubmitState();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        loadProducts();
    }

    private void loadProducts() {
        if (loading) return;
        loading = true;
        showStatus("Đang tải nhà mạng và mệnh giá...");
        api.get("/catalog?limit=100&serviceType=TOPUP").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            loading = false;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new IllegalStateException("TOPUP_CATALOG_UNAVAILABLE");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                productsByNetwork.clear();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject product = items.getJSONObject(i);
                    String network = product.optString("network");
                    if (!product.optBoolean("demo") || network.isEmpty()
                            || !"VND".equals(product.optString("currency"))) continue;
                    productsByNetwork.computeIfAbsent(network, key -> new ArrayList<>()).add(product);
                }
                for (List<JSONObject> products : productsByNetwork.values())
                    products.sort(Comparator.comparingLong(item -> item.optLong("amountMinor")));
                if (productsByNetwork.isEmpty()) {
                    binding.carrierGrid.removeAllViews();
                    binding.amountGrid.removeAllViews();
                    showStatus("Hiện chưa có nhà mạng hoặc mệnh giá để nạp.");
                    updateSubmitState();
                    return;
                }
                selectedNetwork = productsByNetwork.keySet().iterator().next();
                selectedProduct = defaultProduct(productsByNetwork.get(selectedNetwork));
                renderCarriers();
                renderAmounts();
                updateCarrierHint();
                binding.statusText.setVisibility(View.GONE);
                updateSubmitState();
            } catch (Exception error) {
                showStatus("Không tải được danh sách nạp. Hãy thử lại.");
            }
        });
    }

    private void renderCarriers() {
        binding.carrierGrid.removeAllViews();
        for (Map.Entry<String, List<JSONObject>> entry : productsByNetwork.entrySet()) {
            JSONObject product = entry.getValue().get(0);
            String label = displayNetwork(product.optString("name", entry.getKey()));
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setRadius(dp(15));
            card.setCardElevation(0);
            card.setClickable(true);
            card.setFocusable(true);
            TextView text = new TextView(requireContext());
            text.setText(label);
            text.setGravity(Gravity.CENTER);
            text.setTextSize(12);
            card.addView(text, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = dp(48);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            binding.carrierGrid.addView(card, params);
            boolean selected = entry.getKey().equals(selectedNetwork);
            applySelectedStyle(card, text, selected);
            card.setOnClickListener(v -> {
                selectNetwork(entry.getKey());
            });
        }
    }

    private void renderAmounts() {
        binding.amountGrid.removeAllViews();
        List<JSONObject> products = productsByNetwork.get(selectedNetwork);
        if (products == null) return;
        for (JSONObject product : products) {
            long amount = product.optLong("amountMinor");
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setRadius(dp(18));
            card.setCardElevation(0);
            card.setClickable(true);
            card.setFocusable(true);
            LinearLayout content = new LinearLayout(requireContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(dp(13), dp(10), dp(13), dp(10));
            TextView amountText = new TextView(requireContext());
            amountText.setText(ReadSummaryFormatter.formatCurrency(amount, "VND"));
            amountText.setTextSize(15);
            amountText.setTypeface(null, android.graphics.Typeface.BOLD);
            content.addView(amountText);
            TextView priceText = new TextView(requireContext());
            priceText.setTextSize(10);
            LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            priceParams.topMargin = dp(3);
            content.addView(priceText, priceParams);
            card.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = dp(70);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            binding.amountGrid.addView(card, params);
            boolean selected = product == selectedProduct;
            applySelectedStyle(card, amountText, selected);
            priceText.setTextColor(ContextCompat.getColor(requireContext(),
                    selected ? R.color.white : R.color.ink_muted));
            card.setOnClickListener(v -> {
                selectedProduct = product;
                renderAmounts();
                updateSubmitState();
            });
        }
    }

    private void applySelectedStyle(MaterialCardView card, TextView text, boolean selected) {
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), selected ? R.color.navy : R.color.glass_card));
        card.setStrokeColor(ContextCompat.getColor(requireContext(), selected ? R.color.navy : R.color.glass_border));
        card.setStrokeWidth(dp(1));
        text.setTextColor(ContextCompat.getColor(requireContext(), selected ? R.color.white : R.color.ink));
    }

    private String displayNetwork(String value) {
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("A")) return "Viettel";
        if (normalized.equals("B")) return "MobiFone";
        if (normalized.equals("C")) return "VinaPhone";
        return value.trim();
    }

    private JSONObject defaultProduct(List<JSONObject> products) {
        if (products == null || products.isEmpty()) return null;
        for (JSONObject product : products) if (product.optLong("amountMinor") == 100000) return product;
        return products.get(0);
    }

    private void selectNetwork(String network) {
        if (!productsByNetwork.containsKey(network)) return;
        long amount = selectedProduct == null ? 100000 : selectedProduct.optLong("amountMinor");
        selectedNetwork = network;
        selectedProduct = null;
        for (JSONObject product : productsByNetwork.get(network)) {
            if (product.optLong("amountMinor") == amount) {
                selectedProduct = product;
                break;
            }
        }
        if (selectedProduct == null) selectedProduct = defaultProduct(productsByNetwork.get(network));
        renderCarriers();
        renderAmounts();
        updateSubmitState();
    }

    private void updateCarrierHint() {
        if (binding == null) return;
        String suggestedNetwork = networkForPhone(binding.phoneInput.getText() == null
                ? "" : binding.phoneInput.getText().toString());
        if (suggestedNetwork == null || !productsByNetwork.containsKey(suggestedNetwork)) {
            binding.carrierHint.setVisibility(View.GONE);
            return;
        }
        String name = displayNetwork(productsByNetwork.get(suggestedNetwork).get(0).optString("name", suggestedNetwork));
        binding.carrierHint.setText("Gợi ý theo đầu số: " + name + " · Có thể khác nếu đã chuyển mạng");
        binding.carrierHint.setVisibility(View.VISIBLE);
    }

    private void applySuggestedCarrier() {
        String suggestedNetwork = networkForPhone(binding.phoneInput.getText() == null
                ? "" : binding.phoneInput.getText().toString());
        if (suggestedNetwork == null || !productsByNetwork.containsKey(suggestedNetwork)) {
            binding.phoneInput.setError("Chưa nhận diện được nhà mạng. Bạn có thể chọn thủ công.");
            return;
        }
        selectNetwork(suggestedNetwork);
    }

    private String networkForPhone(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        if (phone.length() < 3) return null;
        String prefix = phone.substring(0, 3);
        if (java.util.Arrays.asList("032", "033", "034", "035", "036", "037", "038", "039", "086", "096", "097", "098").contains(prefix)) return "A";
        if (java.util.Arrays.asList("070", "076", "077", "078", "079", "089", "090", "093").contains(prefix)) return "B";
        if (java.util.Arrays.asList("081", "082", "083", "084", "085", "088", "091", "094").contains(prefix)) return "C";
        return null;
    }

    private static String normalizePhone(String rawPhone) {
        if (rawPhone == null) return "";
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.startsWith("84") && digits.length() == 11) return "0" + digits.substring(2);
        return digits;
    }

    private void updateSubmitState() {
        if (binding == null) return;
        String phone = normalizePhone(binding.phoneInput.getText() == null ? "" : binding.phoneInput.getText().toString());
        boolean validPhone = phone.matches("0[0-9]{9}");
        boolean ready = selectedProduct != null && validPhone && !loading;
        binding.topupButton.setEnabled(ready);
        binding.totalAmount.setText(selectedProduct == null ? "Chọn mệnh giá"
                : ReadSummaryFormatter.formatCurrency(selectedProduct.optLong("amountMinor"), "VND"));
        if (selectedProduct != null) binding.topupButton.setText("Nạp ngay · "
                + ReadSummaryFormatter.formatCurrency(selectedProduct.optLong("amountMinor"), "VND"));
        else binding.topupButton.setText("Nạp ngay");
    }

    private void submitTopup() {
        String phone = normalizePhone(binding.phoneInput.getText() == null ? "" : binding.phoneInput.getText().toString());
        if (!phone.matches("0[0-9]{9}")) {
            binding.phoneInput.setError("Nhập số điện thoại gồm 10 chữ số, bắt đầu bằng 0.");
            return;
        }
        if (selectedProduct == null) return;
        UtilitiesHubDialogFragment checkout = UtilitiesHubDialogFragment.newOrderCheckoutInstance(
                "TOPUP", selectedProduct.optString("id"), phone, sourceAccountId);
        checkout.show(getParentFragmentManager(), "UtilityCheckout");
    }

    private void showStatus(String message) {
        binding.statusText.setText(message);
        binding.statusText.setVisibility(View.VISIBLE);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
