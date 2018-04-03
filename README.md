# ![](https://github.com/narcolepticsnowman/BabyORM/blob/master/penguin_logo_small.png?raw=true) BabyORM 
A minimal ORM for accomplishing database tasks.
 
### How the F*** do I use this thing?
###### Required to work
1. The Entity/DTO type MUST have at least one field annotated with ```@PK```
1. Get a new Repo 
   - ``` new BabyRepo<Foo>(){};```<sup>*</sup> or ```BabyRepo.forType(Foo.class)); ```
1. Set a connection supplier (i.e. ConnectionPoolX::getConnection)

<sup>\*Th ```{}``` are necessary to infer the class at runtime, you do NOT need the extra curlies if you extend BabyRepo and specify the type parameter on your class</sup>

###### Fancy bits
If the names of your classes and fields do not match exactly to the names in the database, you will
need to provide the appropriate name annotation. See ```@ColumnName```, ```@TableName```, ```@SchemaName```.

If your primary key is not auto generated by the database, you must set a KeyProvider that returns a new key.

If your key is multi valued, you must provide a MultiValuedKeyProvider




### About
This ORM is meant to provide super light weight ORM functionality without any dependencies.
It provides full CRUD capabilities for any entity object. The plan is to add only the most commonly needed features to keep usage simple.

##### Key features:
    - Easy to learn and use
    - Automagically map object fields to row columns and vice versa
    - Insert, Update, Delete records
    - Query by any set of columns, either ANDed or ORed together
    - Query multiple values for the same key by using any kind of Collection as the value
    - Arbitrary SQL query execution to fetch objects (think views in code).
    - Automatically convert column names to the given Case (for instance, camelCase to snake_case)
    - Support for multi column keys

##### planned features:
    - Support storing regular object types as JSON
    - Support Joins to other tables joined by multiple arbitrary keys
    - Support Transactions

### Why build another ORM?
Other ORMs are over kill when you need to accomplish simple row to object mapping.