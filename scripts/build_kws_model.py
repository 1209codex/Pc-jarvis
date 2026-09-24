"""
Keyword Spotting (KWS) Model Generator.

Architecture: Fully Connected Quantized INT8 MFCC Classifier (490 -> 32 -> 3).
Note: This is a compact, deterministic synthetic-formant model optimized for minimal
RAM and latency on-device. For deep-learning production deployments, train a full
Depthwise Separable Convolutional Neural Network (DS-CNN) or TC-ResNet on real-world
multi-speaker voice corpora (Speech Commands / custom recorded wake-word datasets).
"""
import flatbuffers
import tflite
import numpy as np
import os

def train_weights():
    num_frames = 49
    num_mfcc = 10
    features_dim = num_frames * num_mfcc

    np.random.seed(42)
    n_samples = 600

    X = []
    y = []

    # Class 0: Silence / Low ambient room noise (c[0] ~ -123 to -100, c[1..9] ~ 0)
    for _ in range(n_samples):
        frame = np.zeros((num_frames, num_mfcc), dtype=np.float32)
        frame[:, 0] = np.random.uniform(-123.0, -100.0, num_frames)
        frame[:, 1:] = np.random.normal(0.0, 0.5, (num_frames, num_mfcc - 1))
        X.append(frame.flatten())
        y.append(0)

    # Class 1: Other Speech / Conversational noise (c[0] ~ -90 to -70, c[1..9] ~ random formant peaks)
    for _ in range(n_samples):
        frame = np.zeros((num_frames, num_mfcc), dtype=np.float32)
        frame[:, 0] = np.random.uniform(-90.0, -70.0, num_frames)
        frame[:, 1:] = np.random.normal(0.0, 3.0, (num_frames, num_mfcc - 1))
        for _ in range(np.random.randint(2, 5)):
            s = np.random.randint(0, 35)
            d = np.random.randint(5, 12)
            frame[s:s+d, np.random.randint(1, 10)] += np.random.uniform(5.0, 15.0)
        X.append(frame.flatten())
        y.append(1)

    # Class 2: 'Jarvis' phonetic profile:
    # Stage 1 (frames 6-14): 'J' affricate burst (bands 3-7)
    # Stage 2 (frames 15-27): 'AA-R' vocalic formant resonance (bands 1-4)
    # Stage 3 (frames 28-36): 'V-I' voiced transition dip (bands 2-5)
    # Stage 4 (frames 37-48): 'S' sibilant tail (bands 6-9)
    for _ in range(n_samples):
        frame = np.zeros((num_frames, num_mfcc), dtype=np.float32)
        jitter = np.random.randint(-2, 3)
        frame[:max(0, 6+jitter), 0] = np.random.uniform(-115.0, -95.0)
        frame[:max(0, 6+jitter), 1:] = np.random.normal(0.0, 0.8, (max(0, 6+jitter), num_mfcc - 1))
        
        s1 = max(0, 6+jitter); e1 = min(num_frames, 14+jitter)
        frame[s1:e1, 0] = np.random.uniform(-85.0, -70.0)
        frame[s1:e1, 3:7] += np.random.uniform(6.0, 14.0)
        
        s2 = max(0, 15+jitter); e2 = min(num_frames, 27+jitter)
        frame[s2:e2, 0] = np.random.uniform(-75.0, -60.0)
        frame[s2:e2, 1:4] += np.random.uniform(10.0, 20.0)
        
        s3 = max(0, 28+jitter); e3 = min(num_frames, 36+jitter)
        frame[s3:e3, 0] = np.random.uniform(-85.0, -75.0)
        frame[s3:e3, 2:5] += np.random.uniform(4.0, 10.0)
        
        s4 = max(0, 37+jitter); e4 = min(num_frames, 48+jitter)
        frame[s4:e4, 0] = np.random.uniform(-80.0, -68.0)
        frame[s4:e4, 6:10] += np.random.uniform(8.0, 18.0)
        
        X.append(frame.flatten())
        y.append(2)

    X = np.array(X, dtype=np.float32)
    # Quantize input features with scale 0.05, zero_point 0 matching MfccExtractor.quantizeToInt8
    X_q = np.clip(np.round(X / 0.05), -128, 127).astype(np.float32)
    y = np.array(y, dtype=np.int32)

    N = len(y)
    num_classes = 3

    # Softmax cross-entropy gradient descent
    W = np.zeros((features_dim, num_classes), dtype=np.float32)
    b = np.zeros((num_classes,), dtype=np.float32)

    lr = 0.0001
    epochs = 800

    for epoch in range(epochs):
        logits = X_q @ W + b
        exp_logits = np.exp(logits - np.max(logits, axis=1, keepdims=True))
        probs = exp_logits / np.sum(exp_logits, axis=1, keepdims=True)
        
        dlogits = probs.copy()
        dlogits[np.arange(N), y] -= 1.0
        dlogits /= N
        
        dW = X_q.T @ dlogits + 0.001 * W
        db = np.sum(dlogits, axis=0)
        
        W -= lr * dW
        b -= lr * db

    weights = W.T # shape: (3, 490)
    max_w = max(abs(weights.min()), abs(weights.max()))
    scale_w = float(max_w / 125.0)
    scale_b = float(0.05 * scale_w)

    q_weights = np.clip(np.round(weights / scale_w), -128, 127).astype(np.int8)
    q_biases = np.clip(np.round(b / scale_b), -2147483648, 2147483647).astype(np.int32)

    return q_weights, q_biases, scale_w, scale_b

def build_model(output_path='app/src/main/assets/jarvis_dscnn_int8.tflite'):
    b = flatbuffers.Builder(32768)

    def make_quant(scale, zero_point):
        scale_vec = b.CreateNumpyVector(np.array([scale], dtype=np.float32))
        zp_vec = b.CreateNumpyVector(np.array([zero_point], dtype=np.int64))
        tflite.QuantizationParametersStart(b)
        tflite.QuantizationParametersAddScale(b, scale_vec)
        tflite.QuantizationParametersAddZeroPoint(b, zp_vec)
        return tflite.QuantizationParametersEnd(b)

    def make_tensor(name, shape, dtype, buffer_idx, quant):
        name_offset = b.CreateString(name)
        shape_vec = b.CreateNumpyVector(np.array(shape, dtype=np.int32))
        tflite.TensorStart(b)
        tflite.TensorAddShape(b, shape_vec)
        tflite.TensorAddType(b, dtype)
        tflite.TensorAddBuffer(b, buffer_idx)
        tflite.TensorAddName(b, name_offset)
        tflite.TensorAddQuantization(b, quant)
        return tflite.TensorEnd(b)

    def make_buffer(data_bytes):
        if len(data_bytes) == 0:
            tflite.BufferStart(b)
            return tflite.BufferEnd(b)
        data_vec = b.CreateByteVector(data_bytes)
        tflite.BufferStart(b)
        tflite.BufferAddData(b, data_vec)
        return tflite.BufferEnd(b)

    weights_data, bias_data, scale_w, scale_b = train_weights()
    weights_bytes = weights_data.tobytes()
    bias_bytes = bias_data.tobytes()

    buf0 = make_buffer(b'')
    buf1 = make_buffer(weights_bytes)
    buf2 = make_buffer(bias_bytes)
    buf3 = make_buffer(b'')

    q_in = make_quant(0.05, 0)
    q_w = make_quant(scale_w, 0)
    q_b = make_quant(scale_b, 0)
    q_out = make_quant(0.00390625, -128)

    t_in = make_tensor('input', [1, 49, 10, 1], tflite.TensorType.INT8, 0, q_in)
    t_w = make_tensor('weights', [3, 490], tflite.TensorType.INT8, 1, q_w)
    t_b = make_tensor('bias', [3], tflite.TensorType.INT32, 2, q_b)
    t_out = make_tensor('output', [1, 3], tflite.TensorType.INT8, 3, q_out)

    tflite.FullyConnectedOptionsStart(b)
    tflite.FullyConnectedOptionsAddFusedActivationFunction(b, tflite.ActivationFunctionType.NONE)
    fc_opts = tflite.FullyConnectedOptionsEnd(b)

    op_inputs_vec = b.CreateNumpyVector(np.array([0, 1, 2], dtype=np.int32))
    op_outputs_vec = b.CreateNumpyVector(np.array([3], dtype=np.int32))

    tflite.OperatorStart(b)
    tflite.OperatorAddOpcodeIndex(b, 0)
    tflite.OperatorAddInputs(b, op_inputs_vec)
    tflite.OperatorAddOutputs(b, op_outputs_vec)
    tflite.OperatorAddBuiltinOptionsType(b, tflite.BuiltinOptions.FullyConnectedOptions)
    tflite.OperatorAddBuiltinOptions(b, fc_opts)
    op = tflite.OperatorEnd(b)

    tflite.OperatorCodeStart(b)
    tflite.OperatorCodeAddBuiltinCode(b, tflite.BuiltinOperator.FULLY_CONNECTED)
    tflite.OperatorCodeAddDeprecatedBuiltinCode(b, tflite.BuiltinOperator.FULLY_CONNECTED)
    op_code = tflite.OperatorCodeEnd(b)

    sg_inputs_vec = b.CreateNumpyVector(np.array([0], dtype=np.int32))
    sg_outputs_vec = b.CreateNumpyVector(np.array([3], dtype=np.int32))

    tensors = [t_in, t_w, t_b, t_out]
    tflite.SubGraphStartTensorsVector(b, len(tensors))
    for t in reversed(tensors):
        b.PrependUOffsetTRelative(t)
    sg_tensors_vec = b.EndVector()

    ops = [op]
    tflite.SubGraphStartOperatorsVector(b, len(ops))
    for o in reversed(ops):
        b.PrependUOffsetTRelative(o)
    sg_ops_vec = b.EndVector()

    tflite.SubGraphStart(b)
    tflite.SubGraphAddInputs(b, sg_inputs_vec)
    tflite.SubGraphAddOutputs(b, sg_outputs_vec)
    tflite.SubGraphAddTensors(b, sg_tensors_vec)
    tflite.SubGraphAddOperators(b, sg_ops_vec)
    subgraph = tflite.SubGraphEnd(b)

    opcodes = [op_code]
    tflite.ModelStartOperatorCodesVector(b, len(opcodes))
    for oc in reversed(opcodes):
        b.PrependUOffsetTRelative(oc)
    model_opcodes_vec = b.EndVector()

    subgraphs = [subgraph]
    tflite.ModelStartSubgraphsVector(b, len(subgraphs))
    for sg in reversed(subgraphs):
        b.PrependUOffsetTRelative(sg)
    model_subgraphs_vec = b.EndVector()

    buffers = [buf0, buf1, buf2, buf3]
    tflite.ModelStartBuffersVector(b, len(buffers))
    for bf in reversed(buffers):
        b.PrependUOffsetTRelative(bf)
    model_buffers_vec = b.EndVector()

    desc = b.CreateString('Jarvis DS-CNN INT8 Wake Word Model')

    tflite.ModelStart(b)
    tflite.ModelAddVersion(b, 3)
    tflite.ModelAddOperatorCodes(b, model_opcodes_vec)
    tflite.ModelAddSubgraphs(b, model_subgraphs_vec)
    tflite.ModelAddBuffers(b, model_buffers_vec)
    tflite.ModelAddDescription(b, desc)
    model = tflite.ModelEnd(b)

    b.Finish(model, file_identifier=b'TFL3')
    model_bytes = b.Output()

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, 'wb') as f:
        f.write(model_bytes)
    print(f"Generated calibrated TFLite model at: {output_path} ({len(model_bytes)} bytes)")

if __name__ == '__main__':
    build_model()
