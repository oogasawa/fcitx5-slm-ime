package com.github.oogasawa.slmime;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Kana-kanji IME inference using DJL (Deep Java Library) with TorchScript models.
 *
 * Models: encoder_m512-12.pt and decoder_m512-12.pt (renamed from .ts)
 * Tokenizer: tokenizer.json (char-to-id JSON map)
 *
 * Calling convention (batch-first):
 *   encoder(src[1,srcLen], srcPad[1,srcLen]) -> memory[1,srcLen,512]
 *   decoder(tgt[1,tgtLen], memory[1,srcLen,512], tgtMask[tgtLen,tgtLen],
 *           tgtPad[1,tgtLen], memPad[1,srcLen]) -> logits[1,tgtLen,vocabSize]
 */
public class KanjiInference {

    private static final int BOS_ID = 1;
    private static final int EOS_ID = 2;
    /** Stop when the EOS score comes within this distance of the best score; 0 (the default) turns the rule off.
     *  The rule was made for an older model and ends the output of the current model too early. */
    private static final float EOS_PROXIMITY_THRESHOLD = Float.parseFloat(System.getProperty("stop.eosProx", "0"));
    /** Stop when the last four characters repeat a pair; off by default, like the rule above. */
    private static final boolean STOP_ON_REPEAT = Boolean.parseBoolean(System.getProperty("stop.repeat", "false"));

    public static void main(String[] args) throws Exception {
        String modelDir = System.getProperty("model.dir",
            "model");
        String tokenizerPath = System.getProperty("tokenizer",
            "model/tokenizer.json");

        CharTokenizer tokenizer = CharTokenizer.load(tokenizerPath);
        System.out.println("Tokenizer loaded: vocab_size=" + tokenizer.vocabSize());

        Criteria<NDList, NDList> encoderCriteria = Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Paths.get(modelDir))
            .optModelName("encoder_m512-12")
            .optEngine("PyTorch")
            .optDevice(Device.cpu())
            .build();

        Criteria<NDList, NDList> decoderCriteria = Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Paths.get(modelDir))
            .optModelName("decoder_m512-12")
            .optEngine("PyTorch")
            .optDevice(Device.cpu())
            .build();

        // Server mode: java -jar ... --server 28200
        if (args.length >= 2 && "--server".equals(args[0])) {
            int port = Integer.parseInt(args[1]);
            new KanjiServer(modelDir, tokenizerPath).startAndBlock(port);
            return;
        }

        System.out.println("Loading models from: " + modelDir);

        try (ZooModel<NDList, NDList> encModel = encoderCriteria.loadModel();
             ZooModel<NDList, NDList> decModel = decoderCriteria.loadModel();
             Predictor<NDList, NDList> encoder = encModel.newPredictor();
             Predictor<NDList, NDList> decoder = decModel.newPredictor();
             NDManager manager = NDManager.newBaseManager(Device.cpu())) {

            System.out.println("Models loaded.");

            String[] inputs;
            if (args.length > 0) {
                inputs = args;
            } else {
                inputs = new String[]{"にほんご", "かんじへんかん", "とうきょう", "あいうえお", "きかいがくしゅう"};
            }

            for (String input : inputs) {
                long start = System.currentTimeMillis();
                String result = translate(input, tokenizer, encoder, decoder, manager);
                long elapsed = System.currentTimeMillis() - start;
                System.out.printf("%-20s → %s  (%dms)%n", input, result, elapsed);
            }
        }
    }

    private static String translate(
            String input,
            CharTokenizer tokenizer,
            Predictor<NDList, NDList> encoder,
            Predictor<NDList, NDList> decoder,
            NDManager manager) throws TranslateException {

        List<Integer> srcIds = new ArrayList<>(tokenizer.encodeChars(input));
        srcIds.add(EOS_ID); // the model is trained with the source ending in EOS
        int srcLen = srcIds.size();

        try (NDManager sub = manager.newSubManager()) {
            // Build encoder inputs: src (1, srcLen), srcPad (1, srcLen) all-false
            long[] srcArr = srcIds.stream().mapToLong(Integer::longValue).toArray();
            NDArray src = sub.create(srcArr, new Shape(1, srcLen));
            NDArray srcPad = sub.zeros(new Shape(1, srcLen), DataType.BOOLEAN);

            // Run encoder
            NDList encOut = encoder.predict(new NDList(src, srcPad));
            NDArray memory = encOut.get(0);  // (1, srcLen, 512)

            List<Integer> outputIds = new ArrayList<>();
            List<Long> tgtList = new ArrayList<>();
            tgtList.add((long) BOS_ID);
            String stopReason = "maxlen";

            int maxSteps = srcLen + 5;
            for (int step = 0; step < maxSteps; step++) {
                int tgtLen = tgtList.size();

                // tgt (1, tgtLen)
                long[] tgtArr = tgtList.stream().mapToLong(Long::longValue).toArray();
                NDArray tgt = sub.create(tgtArr, new Shape(1, tgtLen));

                // tgt_mask: upper triangular boolean (tgtLen, tgtLen), true = masked
                boolean[] maskData = new boolean[tgtLen * tgtLen];
                for (int i = 0; i < tgtLen; i++)
                    for (int j = i + 1; j < tgtLen; j++)
                        maskData[i * tgtLen + j] = true;
                NDArray tgtMask = sub.create(maskData, new Shape(tgtLen, tgtLen));

                NDArray tgtPad = sub.zeros(new Shape(1, tgtLen), DataType.BOOLEAN);
                NDArray memPad = sub.zeros(new Shape(1, srcLen), DataType.BOOLEAN);

                NDList decOut = decoder.predict(new NDList(tgt, memory, tgtMask, tgtPad, memPad));
                NDArray logits = decOut.get(0);  // (1, tgtLen, vocabSize)

                // Extract last-position logits: logits[0, tgtLen-1, :]
                NDArray lastLogits = logits.squeeze(0).get(tgtLen - 1);  // (vocabSize,)

                float eosLogit = lastLogits.getFloat(EOS_ID);
                float maxLogit = lastLogits.max().getFloat();
                long nextId = lastLogits.argMax().getLong();

                // Stopping condition 1: EOS token selected
                if (nextId == EOS_ID) {
                    stopReason = "eos";
                    break;
                }

                // Stopping condition 2: EOS proximity (eosLogit >= maxLogit - 8.0, output non-empty)
                if (EOS_PROXIMITY_THRESHOLD > 0 && !outputIds.isEmpty() && eosLogit >= maxLogit - EOS_PROXIMITY_THRESHOLD) {
                    stopReason = "eos_prox";
                    break;
                }

                // Stopping condition 3: 2-gram repetition in last 4 tokens
                int n = outputIds.size();
                if (STOP_ON_REPEAT && n >= 3) {
                    int a = outputIds.get(n - 1);
                    int b = outputIds.get(n - 2);
                    int c = outputIds.get(n - 3);
                    if ((int) nextId == b && a == c) {
                        stopReason = "ngram";
                        break;
                    }
                }

                outputIds.add((int) nextId);
                tgtList.add(nextId);

                // Stopping condition 4: length cap (output >= input length)
                if (outputIds.size() >= srcLen) {
                    stopReason = "length_cap";
                    break;
                }
            }

            return tokenizer.decode(outputIds) + " [" + stopReason + "]";
        }
    }
}
