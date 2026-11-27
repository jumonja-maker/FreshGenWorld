package dev.freshgenerator.road;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import dev.freshgenerator.core.road.RoadTerrain;

/** The road planner's ground: the drainage field's final ground and water (the terrain the game generates). */
final class FieldTerrain implements RoadTerrain {
    private final HydroField field;
    private final HydroField.Column c = new HydroField.Column();

    FieldTerrain(HydroField field) { this.field = field; }

    @Override public void sample(int x, int z, Sample out) {
        field.column(x, z, c);
        out.ground = c.terrain;
        out.width = c.width;
        out.near = c.wetDistance;
        out.water = NO_WATER;
        out.kind = DRY;
        if (c.wet()) {
            out.water = c.water;
            out.kind = c.kind == HydroField.LAKE || c.kind == HydroField.POOL ? LAKE : RIVER;
        } else if (c.terrain < HydroModel.SEA_LEVEL) {
            out.water = HydroModel.SEA_WATER;
            out.kind = SEA;
        }
    }
}
