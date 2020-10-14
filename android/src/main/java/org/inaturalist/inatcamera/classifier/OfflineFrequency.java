package org.inaturalist.inatcamera.classifier;

import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.Calendar;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import timber.log.*;
import java.io.File;


class OfflineFrequency {
    private static final String TAG = "OfflineFrequency";
    private RealmConfiguration mConfig;

    public OfflineFrequency(String realmFile) {
        Timber.tag(TAG).d(String.format("Initializing offline frequency: " + realmFile));

        mConfig = new RealmConfiguration.Builder()
                .assetFile(realmFile)
                .readOnly()
                .schemaVersion(2)
                .build();

        Realm.removeDefaultConfiguration();
        Realm.setDefaultConfiguration(mConfig);
    }

    private Realm getRealm() {
        return Realm.getDefaultInstance();
    }

    // Queries the DB for taxa frequency -
    // Finds all taxa that were identified:
    // A) In the 3-month window of input date (one month before, one month after and the month itself)
    // B) In the 4 square lat/lng location grid (of the input location)
    public List<JsonObject> query(Date date, double latitude, double longitude) {
        // Get 3-month search window
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        Integer currentMonth = cal.get(Calendar.MONTH) + 1; // this method is zero-based (Jan = 0)
        Integer prevMonth = (currentMonth - 1) == 0 ? 12 : currentMonth - 1;
        Integer nextMonth = (currentMonth + 1) == 13 ? 1 : currentMonth + 1;

        // Get 4 square search grid of location lat/lng

        // This is how we determine the bottom-left of the 4-square search grid
        int lat = (int) Math.floor(latitude - 0.5);
        int lng = (int) Math.floor(longitude - 0.5);

        int nextLat = lat == 90 ? -90 : lat + 1;
        int nextLng = lng == 180 ? -180 : lng + 1;

        // Each key is a string of "lat,lng" (e.g. "72,108")
        String[] locationGrid = {
                String.format("%d,%d", nextLat, lng), String.format("%d,%d", nextLat, nextLng),
                String.format("%d,%d", lat, lng), String.format("%d,%d", lat, nextLng)
        };

        // Perform the query
        Realm realm = getRealm();

        RealmQuery<TaxonFrequency> query;
        RealmResults<TaxonFrequency> results;

        // Key = lat,lng
        query = realm.where(TaxonFrequency.class).in("location", locationGrid);
        results = query.findAll();

        JsonArray taxa = new JsonArray();
        Map<String, JsonObject> taxaById = new HashMap<>();

        // Per location grid item, add all relevant months
        for (TaxonFrequency result : results) {
            JsonArray[] allMonths;

            // Each result item is a list of months
            JsonObject months = result.getTaxonMonthData();
            String location = result.location;

            allMonths = new JsonArray[]{
                    months.has(prevMonth.toString()) ? months.getAsJsonArray(prevMonth.toString()) : new JsonArray(),
                    months.has(currentMonth.toString()) ? months.getAsJsonArray(currentMonth.toString()) : new JsonArray(),
                    months.has(nextMonth.toString()) ? months.getAsJsonArray(nextMonth.toString()) : new JsonArray(),
            };

            for (JsonArray currentMonths : allMonths) {
                for (JsonElement iter : currentMonths) {
                    JsonObject newTaxon = iter.getAsJsonObject();
                    String taxonId = newTaxon.get("i").getAsString();

                    if (!taxaById.containsKey(taxonId)) {
                        taxaById.put(taxonId, newTaxon);
                    } else {
                        JsonObject taxon = taxaById.get(taxonId);
                        taxon.addProperty("c", taxon.get("c").getAsInt() + newTaxon.get("c").getAsInt());
                        taxaById.put(taxonId, taxon);
                    }
                }
            }
        }

        realm.close();

        List<JsonObject> sortedTaxa = new ArrayList<>();

        // Sort by count
        for (JsonObject taxon : taxaById.values()) {
            sortedTaxa.add(taxon);
        }

        Collections.sort(sortedTaxa, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject t1, JsonObject t2) {
                return t2.get("c").getAsInt() - t1.get("c").getAsInt();
            }
        });

        return sortedTaxa;
    }
}
