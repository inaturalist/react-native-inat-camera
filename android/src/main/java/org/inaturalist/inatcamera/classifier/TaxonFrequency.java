package org.inaturalist.inatcamera.classifier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

public class TaxonFrequency extends RealmObject {
    @PrimaryKey  public String location; // location = lat,lng (rounded to int)

    public String taxonMonthData;

    public JsonObject getTaxonMonthData() {
        Gson gson = new Gson();
        JsonObject json = gson.fromJson(taxonMonthData, JsonObject.class);

        return json;
    }
}
