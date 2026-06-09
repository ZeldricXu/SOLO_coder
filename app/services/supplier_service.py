from __future__ import annotations
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models.supplier import Supplier
from app.schemas.warehouse import SupplierCreate, SupplierUpdate
from app.utils.exceptions import SupplierNotFoundException, DuplicateCodeException
from app.utils.helpers import generate_supplier_code, get_current_utc_time


class SupplierService:
    def __init__(self, db: Session):
        self.db = db

    def get_supplier(self, supplier_id: int) -> Supplier:
        supplier = self.db.get(Supplier, supplier_id)
        if not supplier:
            raise SupplierNotFoundException(supplier_id)
        return supplier

    def get_supplier_by_code(self, code: str) -> Supplier | None:
        return (
            self.db.query(Supplier)
            .filter(func.lower(Supplier.code) == func.lower(code))
            .first()
        )

    def list_suppliers(
        self,
        skip: int = 0,
        limit: int = 100,
        is_active: bool | None = None,
        city: str | None = None,
        credit_rating: str | None = None,
        search: str | None = None,
    ) -> list[Supplier]:
        query = self.db.query(Supplier)

        if is_active is not None:
            query = query.filter(Supplier.is_active == is_active)
        if city:
            query = query.filter(func.lower(Supplier.city).contains(func.lower(city)))
        if credit_rating:
            query = query.filter(Supplier.credit_rating == credit_rating)
        if search:
            search_lower = search.lower()
            query = query.filter(
                func.or_(
                    func.lower(Supplier.name).contains(search_lower),
                    func.lower(Supplier.code).contains(search_lower),
                    func.lower(Supplier.contact_person).contains(search_lower),
                )
            )

        return query.order_by(Supplier.id.desc()).offset(skip).limit(limit).all()

    def count_suppliers(
        self,
        is_active: bool | None = None,
        city: str | None = None,
        credit_rating: str | None = None,
        search: str | None = None,
    ) -> int:
        query = self.db.query(func.count(Supplier.id))

        if is_active is not None:
            query = query.filter(Supplier.is_active == is_active)
        if city:
            query = query.filter(func.lower(Supplier.city).contains(func.lower(city)))
        if credit_rating:
            query = query.filter(Supplier.credit_rating == credit_rating)
        if search:
            search_lower = search.lower()
            query = query.filter(
                func.or_(
                    func.lower(Supplier.name).contains(search_lower),
                    func.lower(Supplier.code).contains(search_lower),
                    func.lower(Supplier.contact_person).contains(search_lower),
                )
            )

        return query.scalar() or 0

    def create_supplier(self, supplier_in: SupplierCreate) -> Supplier:
        existing = self.get_supplier_by_code(supplier_in.code)
        if existing:
            raise DuplicateCodeException(supplier_in.code, "Supplier")

        if not supplier_in.code:
            supplier_in.code = generate_supplier_code()

        supplier = Supplier(
            **supplier_in.model_dump(),
            created_at=get_current_utc_time(),
            updated_at=get_current_utc_time(),
        )
        self.db.add(supplier)
        self.db.flush()
        self.db.refresh(supplier)
        return supplier

    def update_supplier(
        self, supplier_id: int, supplier_in: SupplierUpdate
    ) -> Supplier:
        supplier = self.get_supplier(supplier_id)

        update_data = supplier_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(supplier, key, value)

        supplier.updated_at = get_current_utc_time()
        self.db.flush()
        self.db.refresh(supplier)
        return supplier

    def delete_supplier(self, supplier_id: int) -> None:
        supplier = self.get_supplier(supplier_id)
        self.db.delete(supplier)
        self.db.flush()

    def set_credit_rating(self, supplier_id: int, rating: str) -> Supplier:
        supplier = self.get_supplier(supplier_id)
        supplier.credit_rating = rating
        supplier.updated_at = get_current_utc_time()
        self.db.flush()
        self.db.refresh(supplier)
        return supplier

    def set_payment_terms(self, supplier_id: int, terms: str) -> Supplier:
        supplier = self.get_supplier(supplier_id)
        supplier.payment_terms = terms
        supplier.updated_at = get_current_utc_time()
        self.db.flush()
        self.db.refresh(supplier)
        return supplier

    def get_supplier_stats(self) -> dict:
        total = self.count_suppliers()
        active = self.count_suppliers(is_active=True)
        inactive = self.count_suppliers(is_active=False)

        rating_stats = (
            self.db.query(
                Supplier.credit_rating,
                func.count(Supplier.id).label("count"),
            )
            .filter(Supplier.credit_rating.isnot(None))
            .group_by(Supplier.credit_rating)
            .all()
        )

        city_stats = (
            self.db.query(
                Supplier.city,
                func.count(Supplier.id).label("count"),
            )
            .filter(Supplier.city.isnot(None))
            .group_by(Supplier.city)
            .order_by(func.count(Supplier.id).desc())
            .limit(10)
            .all()
        )

        return {
            "total": total,
            "active": active,
            "inactive": inactive,
            "by_rating": {rating: count for rating, count in rating_stats},
            "top_cities": [{"city": city, "count": count} for city, count in city_stats],
        }

    def get_suppliers_by_rating(self, min_rating: str) -> list[Supplier]:
        ratings_order = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"]
        try:
            min_index = ratings_order.index(min_rating)
            valid_ratings = ratings_order[: min_index + 1]
        except ValueError:
            valid_ratings = [min_rating]

        return (
            self.db.query(Supplier)
            .filter(Supplier.credit_rating.in_(valid_ratings))
            .filter(Supplier.is_active)
            .order_by(Supplier.credit_rating.asc())
            .all()
        )

    def get_suppliers_by_lead_time(self, max_days: int) -> list[Supplier]:
        return (
            self.db.query(Supplier)
            .filter(Supplier.lead_time_days <= max_days)
            .filter(Supplier.is_active)
            .order_by(Supplier.lead_time_days.asc())
            .all()
        )

    def bulk_activate_suppliers(self, supplier_ids: list[int]) -> int:
        count = (
            self.db.query(Supplier)
            .filter(Supplier.id.in_(supplier_ids))
            .update(
                {
                    Supplier.is_active: True,
                    Supplier.updated_at: get_current_utc_time(),
                }
            )
        )
        self.db.flush()
        return count

    def bulk_deactivate_suppliers(self, supplier_ids: list[int]) -> int:
        count = (
            self.db.query(Supplier)
            .filter(Supplier.id.in_(supplier_ids))
            .update(
                {
                    Supplier.is_active: False,
                    Supplier.updated_at: get_current_utc_time(),
                }
            )
        )
        self.db.flush()
        return count


def create_supplier_service(db: Session) -> SupplierService:
    return SupplierService(db)
