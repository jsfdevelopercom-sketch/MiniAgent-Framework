package com.miniagent.core;

import java.util.Objects;

/**
 * ModelRoute contains the selected model configuration for each stage
 * of a MiniAgent run.
 *
 * The Agent should not hardcode model choices internally.
 * It should receive a ModelRoute from ModelRouter.
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

    public ModelRoute(
            String generatorModel,
            String criticModel,
            String repairModel,
            String synthesizerModel,
            double generatorTemperature,
            double criticTemperature,
            double repairTemperature,
            double synthesizerTemperature) {
        this.generatorModel = requireModel(generatorModel, "generatorModel");
        this.criticModel = requireModel(criticModel, "criticModel");
        this.repairModel = requireModel(repairModel, "repairModel");
        this.synthesizerModel = requireModel(synthesizerModel, "synthesizerModel");

        this.generatorTemperature = clampTemperature(generatorTemperature);
        this.criticTemperature = clampTemperature(criticTemperature);
        this.repairTemperature = clampTemperature(repairTemperature);
        this.synthesizerTemperature = clampTemperature(synthesizerTemperature);
    }

    public String getGeneratorModel() {
        return generatorModel;
    }

    public String getCriticModel() {
        return criticModel;
    }

    public String getRepairModel() {
        return repairModel;
    }

    public String getSynthesizerModel() {
        return synthesizerModel;
    }

    public double getGeneratorTemperature() {
        return generatorTemperature;
    }

    public double getCriticTemperature() {
        return criticTemperature;
    }

    public double getRepairTemperature() {
        return repairTemperature;
    }

    public double getSynthesizerTemperature() {
        return synthesizerTemperature;
    }

    public boolean isCriticGemini() {
        return criticModel.toLowerCase().startsWith("gemini");
    }

    private static String requireModel(String model, String fieldName) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return model.trim();
    }

    private static double clampTemperature(double temperature) {
        if (Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            return 0.0;
        }
        if (temperature < 0.0) {
            return 0.0;
        }
        if (temperature > 2.0) {
            return 2.0;
        }
        return temperature;
    }

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
}