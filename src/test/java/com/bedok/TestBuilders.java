package com.bedok;

import com.bedok.common.model.BaseEntity;
import com.bedok.property.Property;
import com.bedok.property.PropertyStatus;
import com.bedok.room.GenderRule;
import com.bedok.room.Room;
import com.bedok.room.RoomStatus;
import com.bedok.worker.Gender;
import com.bedok.worker.Worker;
import com.bedok.worker.WorkerStatus;

import java.util.UUID;

public final class TestBuilders {

    private TestBuilders() {
    }

    public static WorkerBuilder aWorker() {
        return new WorkerBuilder();
    }

    public static RoomBuilder aRoom() {
        return new RoomBuilder();
    }

    public static PropertyBuilder aProperty() {
        return new PropertyBuilder();
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static class WorkerBuilder {
        private UUID id = UUID.randomUUID();
        private UUID agencyId = IntegrationTestBase.DEFAULT_AGENCY_ID;
        private String firstName = "Jan";
        private String lastName = "Kowalski";
        private Gender gender = Gender.MALE;
        private WorkerStatus status = WorkerStatus.ACTIVE;

        public WorkerBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public WorkerBuilder agencyId(UUID agencyId) {
            this.agencyId = agencyId;
            return this;
        }

        public WorkerBuilder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public WorkerBuilder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public WorkerBuilder gender(Gender gender) {
            this.gender = gender;
            return this;
        }

        public Worker build() {
            var worker = new Worker();
            setId(worker, id);
            worker.setAgencyId(agencyId);
            worker.setInternalId("W-" + id.toString().substring(0, 8));
            worker.setFirstName(firstName);
            worker.setLastName(lastName);
            worker.setGender(gender);
            worker.setStatus(status);
            return worker;
        }
    }

    public static class RoomBuilder {
        private UUID id = UUID.randomUUID();
        private UUID agencyId = IntegrationTestBase.DEFAULT_AGENCY_ID;
        private UUID propertyId = UUID.randomUUID();
        private String name = "Room 1";
        private int capacity = 4;
        private int blockedSpots = 0;
        private GenderRule genderRule = GenderRule.ANY;
        private RoomStatus status = RoomStatus.ACTIVE;

        public RoomBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public RoomBuilder agencyId(UUID agencyId) {
            this.agencyId = agencyId;
            return this;
        }

        public RoomBuilder capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        public RoomBuilder blockedSpots(int blockedSpots) {
            this.blockedSpots = blockedSpots;
            return this;
        }

        public RoomBuilder genderRule(GenderRule genderRule) {
            this.genderRule = genderRule;
            return this;
        }

        public RoomBuilder status(RoomStatus status) {
            this.status = status;
            return this;
        }

        public RoomBuilder name(String name) {
            this.name = name;
            return this;
        }

        public Room build() {
            var room = new Room();
            setId(room, id);
            room.setAgencyId(agencyId);
            room.setPropertyId(propertyId);
            room.setName(name);
            room.setCapacity(capacity);
            room.setBlockedSpots(blockedSpots);
            room.setGenderRule(genderRule);
            room.setStatus(status);
            return room;
        }
    }

    public static class PropertyBuilder {
        private UUID id = UUID.randomUUID();
        private UUID agencyId = IntegrationTestBase.DEFAULT_AGENCY_ID;
        private String name = "Test Property";
        private PropertyStatus status = PropertyStatus.ACTIVE;

        public PropertyBuilder status(PropertyStatus status) {
            this.status = status;
            return this;
        }

        public PropertyBuilder name(String name) {
            this.name = name;
            return this;
        }

        public Property build() {
            var property = new Property();
            setId(property, id);
            property.setAgencyId(agencyId);
            property.setName(name);
            property.setStatus(status);
            return property;
        }
    }
}
