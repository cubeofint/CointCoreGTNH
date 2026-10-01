package coint.worldtravel;

public class WorldTravelViewEntry {

    public String id;
    public String name;
    public String description;
    public String requirementText;
    public int dimension;
    public boolean accessible;

    public WorldTravelViewEntry() {}

    public WorldTravelViewEntry(String id, String name, String description, String requirementText, int dimension,
        boolean accessible) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.requirementText = requirementText;
        this.dimension = dimension;
        this.accessible = accessible;
    }
}
