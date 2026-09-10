package com.beduno;

import com.beduno.bed.Bed;
import com.beduno.bed.BedStatus;
import com.beduno.common.model.BaseEntity;
import com.beduno.property.Property;
import com.beduno.property.PropertyStatus;
import com.beduno.room.GenderRule;
import com.beduno.room.Room;
import com.beduno.room.RoomStatus;
import com.beduno.worker.Gender;
import com.beduno.worker.Worker;
import com.beduno.worker.WorkerStatus;

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

    public static BedBuilder aBed() {
        return new BedBuilder();
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
        private GenderRule genderRule = GenderRule.MIXED;
        private RoomStatus status = RoomStatus.ACTIVE;

        public RoomBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public RoomBuilder agencyId(UUID agencyId) {
            this.agencyId = agencyId;
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
            room.setRoomNumber(name);
            room.setGenderRule(genderRule);
            room.setStatus(status);
            return room;
        }
    }

    public static class BedBuilder {
        private UUID id = UUID.randomUUID();
        private UUID agencyId = IntegrationTestBase.DEFAULT_AGENCY_ID;
        private UUID roomId = UUID.randomUUID();
        private String label = "1";
        private BedStatus status = BedStatus.ACTIVE;

        public BedBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public BedBuilder agencyId(UUID agencyId) {
            this.agencyId = agencyId;
            return this;
        }

        public BedBuilder roomId(UUID roomId) {
            this.roomId = roomId;
            return this;
        }

        public BedBuilder label(String label) {
            this.label = label;
            return this;
        }

        public BedBuilder status(BedStatus status) {
            this.status = status;
            return this;
        }

        public Bed build() {
            var bed = new Bed();
            setId(bed, id);
            bed.setAgencyId(agencyId);
            bed.setRoomId(roomId);
            bed.setLabel(label);
            bed.setStatus(status);
            return bed;
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
