public class Dog {
    private int hunger;
    private String name;
    private static int population = 0;

    public Dog(String name, int hunger) {
        this.name = name;
        this.hunger = hunger;
        population += 1;
    }

    public void feed(int food) {
        int newHunger = this.hunger - food;
        this.hunger = newHunger;
        System.out.println(this);
    }

    public String toString() {
        return this.name + " is one of " + population + " and is this hungry: " + this.hunger;
    }

    public static void main(String[] args) {
        Dog woofie = new Dog("woofie", 25);
        System.out.println(woofie);
        woofie.feed(10);
    }
}

