import onnxruntime as ort
import numpy as np
import json
import math

# Load feature names
with open('mobile/src/main/assets/selected_features_ppg_green.json') as f:
    schema = json.load(f)
    feats = schema['selected_features']

print(f"Feature count: {len(feats)}")
print(f"First 5: {feats[:5]}")

# Simulate realistic PPG at 25Hz, 180s
fs = 25
seconds = 180
hr_bpm = 78.0
n = fs * seconds
t_vals = [i / fs for i in range(n)]
freq_hz = hr_bpm / 60.0
values = [
    100000 + 4000 * math.sin(2 * math.pi * freq_hz * ti)
           + 1000 * math.sin(4 * math.pi * freq_hz * ti)
    for ti in t_vals
]

# Detect peaks
threshold = sum(values) / len(values)
min_dist = int(fs * 0.33)
peaks = []
for i in range(1, len(values) - 1):
    if values[i] > threshold and values[i] > values[i-1] and values[i] >= values[i+1]:
        if not peaks or (i - peaks[-1]) >= min_dist:
            peaks.append(i)

rr = [(peaks[i+1] - peaks[i]) * 1000.0 / fs for i in range(len(peaks) - 1)]
rr = [r for r in rr if 300 <= r <= 2000]
mean_rr = sum(rr) / len(rr)
rr_mean_sub = [v - mean_rr for v in rr]
sdnn = (sum(d*d for d in rr_mean_sub) / max(len(rr_mean_sub) - 1, 1)) ** 0.5
diffs = [rr[i+1] - rr[i] for i in range(len(rr) - 1)]
rmssd = (sum(d*d for d in diffs) / max(len(diffs), 1)) ** 0.5

print(f"Peaks: {len(peaks)}, RR: {len(rr)}, MeanRR: {mean_rr:.1f}ms, SDNN: {sdnn:.2f}ms, RMSSD: {rmssd:.2f}ms")

# Build realistic feature vector (key features filled, rest 0)
vec = np.zeros((1, 99), dtype=np.float32)
feature_map = {
    'num_peaks': float(len(peaks)),
    'n_peaks': float(len(peaks)),
    'hrv_HRV_MeanNN': mean_rr,
    'mean_rr_ms': mean_rr,
    'ibi_mean_ms': mean_rr,
    'hrv_HRV_SDNN': sdnn,
    'std_rr_ms': sdnn,
    'hrv_HRV_RMSSD': rmssd,
    'rmssd_ms': rmssd,
    'mean_hr_bpm': 60000.0 / mean_rr,
    'heart_rate_bpm': 60000.0 / mean_rr,
}
for i, name in enumerate(feats):
    if name in feature_map:
        vec[0, i] = feature_map[name]

sess = ort.InferenceSession('mobile/src/main/assets/stress_ppg_green_model.onnx')
iname = sess.get_inputs()[0].name
out = sess.run(None, {iname: vec})
print(f"\nRealistic sparse vector result:")
print(f"  label={out[0][0]}, prob={out[1][0]}")

# Test edge cases
for label, fill_val in [('zeros', 0.0), ('ones', 1.0)]:
    inp = np.full((1, 99), fill_val, dtype=np.float32)
    out2 = sess.run(None, {iname: inp})
    print(f"  {label}: label={out2[0][0]}, prob={out2[1][0]}")

# Verify ONNX output type
print("\nONNX output types:")
for o in sess.get_outputs():
    print(f"  {o.name}: {o.type}")
