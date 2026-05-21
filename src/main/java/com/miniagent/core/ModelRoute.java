package com.miniagent.core;

import java.util.Locale;
import java.util.Objects;

/**
 * ModelRoute is the immutable model selection object for one MiniAgent run.
 *
 * The route is created by ModelRouter and then consumed by Agent,
 * SafeThoughtExecutor,
 * MiniAgentWorker, MiniAgentEvaluator, and OutputSynthesizer.
 *
 * This class intentionally does not decide anything by itself. It simply stores
 * the chosen model for each stage:
 *
 * - generatorModel: first draft generation
 * - criticModel: evaluator / critic stage
 * - repairModel: repair or replan stage
 * - synthesizerModel: final formatting stage
 *
 * The temperature fields are still primitive doubles for backward compatibility
 * with the existing project. Because primitive double cannot represent "not
 * applicable", ModelRouter uses a neutral placeholder for models that must not
 * receive a custom temperature. Provider clients remain responsible for
 * omitting
 * temperature from the actual HTTP request when the selected model rejects it.
 */
public class ModelRoute {

    private final String generatorModel;
    private final String criticModel;
    private final String repairModel;
    private final String synthesizerModel;

    private final double generatorTemperature;
    private final double criticTemperature;
    private final double repairTemperature;
    private final double synthesizerTemperature;

    /**
     * Creates a complete per-stage model route.
     *
     * A future debugger should read this constructor as the boundary between
     * routing and execution. ModelRouter has already decided which models to use;
     * this constructor only validates and normalizes the immutable route values.
     *
     * The temperature values are clamped defensively because downstream clients
     * may log these values or forward them to non-reasoning models. Reasoning
     * models are handled by the provider clients, which omit unsupported fields.
     */
    public ModelRoute(
            String generatorModel,
            String criticModel,
            String repairModel,
            String synthesizerModel,
            double generatorTemperature,
            double criticTemperature,
            double repairTemperature,
            double synthesizerTemperature) {
        /*
         * Blank model names cause confusing provider errors later. Fail early here
         * so the stack trace points to routing/configuration rather than HTTP.
         */
        this.generatorModel = requireModel(generatorModel, "generatorModel");
        this.criticModel = requireModel(criticModel, "criticModel");
        this.repairModel = requireModel(repairModel, "repairModel");
        this.synthesizerModel = requireModel(synthesizerModel, "synthesizerModel");

        /*
         * Keep the route safe even if a caller accidentally passes NaN, infinity,
         * or an out-of-range value. This is a route-level guard; provider-specific
         * rules are still enforced inside the HTTP clients.
         */
        this.generatorTemperature = clampTemperature(generatorTemperature);
        this.criticTemperature = clampTemperature(criticTemperature);
        this.repairTemperature = clampTemperature(repairTemperature);
        this.synthesizerTemperature = clampTemperature(synthesizerTemperature);
    }

    /**
     * Returns the model used by MiniAgentWorker for the initial answer draft.
     */
    public String getGeneratorModel() {
        return generatorModel;
    }

    /**
     * Returns the model used by MiniAgentEvaluator for critic/evaluation.
     */
    public String getCriticModel() {
        return criticModel;
    }

    /**
     * Returns the model used by MiniAgentWorker when the agent repairs a draft.
     */
    public String getRepairModel() {
        return repairModel;
    }

    /**
     * Returns the model used by OutputSynthesizer for final non-code formatting.
     */
    public String getSynthesizerModel() {
        return synthesizerModel;
    }

    /**
     * Returns the route-level generation temperature.
     *
     * For GPT-5/o-series style models this may be a neutral placeholder. The
     * OpenAI client should still omit temperature from the actual payload.
     */
    public double getGeneratorTemperature() {
        return generatorTemperature;
    }

    /**
     * Returns the route-level critic temperature.
     */
    public double getCriticTemperature() {
        return criticTemperature;
    }

    /**
     * Returns the route-level repair temperature.
     */
    public double getRepairTemperature() {
        return repairTemperature;
    }

    /**
     * Returns the route-level synthesis temperature.
     */
    public double getSynthesizerTemperature() {
        return synthesizerTemperature;
    }

    /**
     * Convenience helper for older evaluator code.
     *
     * Newer code should generally route by model prefix in the provider client,
     * but this helper is kept because existing Agent/Evaluator code may still use
     * it to decide a Gemini-specific parsing path.
     */
    public boolean isCriticGemini() {
        return criticModel.toLowerCase(Locale.ROOT).startsWith("gemini");
    }

    /**
     * Convenience helper for debugging provider routing in logs.
     */
    public boolean isGeneratorOpenAi() {
        return isOpenAiModel(generatorModel);
    }

    /**
     * Convenience helper for debugging provider routing in logs.
     */
    public boolean isCriticClaude() {
        return criticModel.toLowerCase(Locale.ROOT).startsWith("claude");
    }

    /**
     * Convenience helper for debugging provider routing in logs.
     */
    public boolean isRepairOpenAi() {
        return isOpenAiModel(repairModel);
    }

    /**
     * Validates a model field and trims it.
     *
     * This method is intentionally private because ModelRoute should not become a
     * general model registry. It only protects this immutable route object.
     */
    private static String requireModel(String model, String fieldName) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }

        return model.trim();
    }

    /**
     * Clamps a primitive temperature value into the standard model API range.
     *
     * A neutral fallback of 0.0 is used for invalid numeric values because that is
     * the safest deterministic setting for models that accept temperature.
     */
    private static double clampTemperature(double temperature) {
        if (Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            return 0.0d;
        }

        if (temperature < 0.0d) {
            return 0.0d;
        }

        if (temperature > 2.0d) {
            return 2.0d;
        }

        return temperature;
    }

    /**
     * Detects whether a model name belongs to OpenAI for logging helpers.
     */
    private static boolean isOpenAiModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String normalized = model.trim().toLowerCase(Locale.ROOT);

        return normalized.startsWith("gpt-")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4")
                || normalized.contains("deep-research");
    }

    /**
     * Human-readable route dump for Railway logs and local debugging.
     */
    @Override
    public String toString() {
        return "ModelRoute{" +
                "generatorModel='" + generatorModel + '\'' +
                ", criticModel='" + criticModel + '\'' +
                ", repairModel='" + repairModel + '\'' +
                ", synthesizerModel='" + synthesizerModel + '\'' +
                ", generatorTemperature=" + generatorTemperature +
                ", criticTemperature=" + criticTemperature +
                ", repairTemperature=" + repairTemperature +
                ", synthesizerTemperature=" + synthesizerTemperature +
                '}';
    }

    /**
     * Equality is useful in tests and route diagnostics.
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ModelRoute that)) {
            return false;
        }

        return Double.compare(generatorTemperature, that.generatorTemperature) == 0
                && Double.compare(criticTemperature, that.criticTemperature) == 0
                && Double.compare(repairTemperature, that.repairTemperature) == 0
                && Double.compare(synthesizerTemperature, that.synthesizerTemperature) == 0
                && generatorModel.equals(that.generatorModel)
                && criticModel.equals(that.criticModel)
                && repairModel.equals(that.repairModel)
                && synthesizerModel.equals(that.synthesizerModel);
    }

    /**
     * Hash implementation paired with equals for safe use in tests/maps.
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                generatorModel,
                criticModel,
                repairModel,
                synthesizerModel,
                generatorTemperature,
                criticTemperature,
                repairTemperature,
                synthesizerTemperature);
    }
}